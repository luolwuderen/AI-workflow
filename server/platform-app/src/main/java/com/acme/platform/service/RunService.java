package com.acme.platform.service;

import com.acme.platform.api.EventView;
import com.acme.platform.api.NodeRunView;
import com.acme.platform.api.RunView;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.ValidationIssue;
import com.acme.workflow.core.model.ValidationResult;
import com.acme.workflow.core.validation.GraphValidator;
import com.acme.workflow.infra.persistence.AppEntity;
import com.acme.workflow.infra.persistence.JsonCodec;
import com.acme.workflow.infra.persistence.NodeRunEntity;
import com.acme.workflow.infra.persistence.NodeRunRepository;
import com.acme.workflow.infra.persistence.PersistenceExecutionObserver;
import com.acme.workflow.infra.persistence.RunEntity;
import com.acme.workflow.infra.persistence.RunEventEntity;
import com.acme.workflow.infra.persistence.RunEventRepository;
import com.acme.workflow.infra.persistence.RunRepository;
import com.acme.workflow.runtime.FormInputValidator;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.RunResult;
import com.acme.workflow.runtime.RunStatus;
import com.acme.workflow.runtime.WorkflowExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RunService {
    private final AppService apps;
    private final RunRepository runs;
    private final NodeRunRepository nodeRuns;
    private final RunEventRepository events;
    private final JsonCodec json;
    private final GraphValidator validator;
    private final WorkflowExecutor workflowExecutor;
    private final PersistenceExecutionObserver observer;
    private final HumanInputCoordinator humanInputs;
    private final ExecutorService workers;
    private final Duration heartbeatInterval;

    public RunService(AppService apps, RunRepository runs, NodeRunRepository nodeRuns, RunEventRepository events,
                      JsonCodec json, GraphValidator validator, WorkflowExecutor workflowExecutor,
                      PersistenceExecutionObserver observer, HumanInputCoordinator humanInputs,
                      ExecutorService workflowWorkers,
                      @Value("${workflow.sse.heartbeat-interval:15s}") Duration heartbeatInterval) {
        this.apps = apps; this.runs = runs; this.nodeRuns = nodeRuns; this.events = events; this.json = json;
        this.validator = validator; this.workflowExecutor = workflowExecutor; this.observer = observer;
        this.humanInputs = humanInputs; this.workers = workflowWorkers;
        this.heartbeatInterval = heartbeatInterval;
    }

    public RunView start(String appId, Map<String, Object> inputs) {
        AppEntity app = apps.entity(appId);
        GraphDocument graph = json.graph(app.getDraftJson());
        ValidationResult validation = validator.validate(graph);
        List<ValidationIssue> fatal = validation.issues().stream().filter(issue -> !"UNSUPPORTED_NODE".equals(issue.code())).toList();
        if (!fatal.isEmpty()) throw new GraphValidationException(new ValidationResult(fatal));
        String id = UUID.randomUUID().toString();
        Map<String, Object> safeInputs = inputs == null ? Map.of() : inputs;
        RunEntity run = runs.saveAndFlush(new RunEntity(id, appId, json.write(graph), json.write(safeInputs), Instant.now()));
        workers.submit(() -> executeSafely(id, graph, safeInputs));
        return view(run);
    }

    public RunView get(String id) { return view(entity(id)); }

    public RunView resume(String id, String nodeId, Map<String, Object> inputs) {
        RunEntity run = entity(id);
        if (run.getStatus() != RunStatus.PAUSED) throw new RunStateException("运行当前不在人工输入等待状态。");
        GraphNode node = json.graph(run.getGraphJson()).nodes().stream()
                .filter(item -> item.id().equals(nodeId) && "human-input".equals(item.type())).findFirst()
                .orElseThrow(() -> new RunStateException("该运行中不存在 Human Input 节点：" + nodeId));
        Map<String, Object> normalized;
        try {
            normalized = FormInputValidator.validate(node, inputs == null ? Map.of() : inputs);
        } catch (NodeExecutionException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        }
        if (!humanInputs.submit(id, nodeId, normalized))
            throw new RunStateException("该节点的等待已结束，或服务重启后无法原位置恢复，请重新运行。");
        return view(entity(id));
    }

    public List<EventView> events(String id, long after) {
        entity(id);
        return events.findByRunIdAndIdGreaterThanOrderByIdAsc(id, Math.max(0, after)).stream().map(this::eventView).toList();
    }

    public SseEmitter stream(String id, long after) {
        entity(id);
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        AtomicBoolean active = new AtomicBoolean(true);
        emitter.onCompletion(() -> active.set(false));
        emitter.onTimeout(() -> active.set(false));
        emitter.onError(error -> active.set(false));
        workers.submit(() -> streamEvents(id, Math.max(0, after), emitter, active));
        return emitter;
    }

    private void executeSafely(String id, GraphDocument graph, Map<String, Object> inputs) {
        try {
            workflowExecutor.execute(id, graph, inputs, observer);
        } catch (Throwable error) {
            String message = error.getMessage() == null ? "运行执行器异常。" : error.getMessage();
            try {
                observer.emit(id, "RUN_FAILED", null, Map.of("error", message));
                observer.runFinished(id, new RunResult(RunStatus.FAILED, Map.of(), message));
            } catch (RuntimeException ignored) {
                // The original execution failure remains available in the worker log; no implicit retry is attempted.
            }
        }
    }

    private void streamEvents(String id, long after, SseEmitter emitter, AtomicBoolean active) {
        long cursor = after;
        long lastHeartbeat = System.nanoTime();
        try {
            while (active.get()) {
                List<RunEventEntity> batch = events.findByRunIdAndIdGreaterThanOrderByIdAsc(id, cursor);
                for (RunEventEntity event : batch) {
                    if (!active.get()) return;
                    EventView view = eventView(event);
                    emitter.send(SseEmitter.event().id(String.valueOf(view.sequence())).name(view.type()).data(view));
                    cursor = view.sequence();
                    lastHeartbeat = System.nanoTime();
                    if ("RUN_SUCCEEDED".equals(view.type()) || "RUN_FAILED".equals(view.type())) {
                        emitter.complete();
                        return;
                    }
                }
                RunStatus status = entity(id).getStatus();
                if (batch.isEmpty() && (status == RunStatus.SUCCEEDED || status == RunStatus.FAILED)) {
                    emitter.complete();
                    return;
                }
                if (System.nanoTime() - lastHeartbeat >= heartbeatInterval.toNanos()) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = System.nanoTime();
                }
                Thread.sleep(500);
            }
        } catch (IOException | IllegalStateException error) {
            active.set(false);
            emitter.complete();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            active.set(false);
            emitter.complete();
        }
    }

    private RunEntity entity(String id) {
        return runs.findById(id).orElseThrow(() -> new NotFoundException("运行不存在：" + id));
    }

    private RunView view(RunEntity run) {
        List<NodeRunView> nodes = nodeRuns.findByRunIdOrderByStartedAtAsc(run.getId()).stream().map(this::nodeView).toList();
        return new RunView(run.getId(), run.getAppId(), run.getStatus(), json.graph(run.getGraphJson()), json.map(run.getInputsJson()),
                json.map(run.getOutputsJson()), run.getError(), run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt(), nodes);
    }

    private NodeRunView nodeView(NodeRunEntity node) {
        return new NodeRunView(node.getNodeId(), node.getNodeType(), node.getStatus(), json.map(node.getInputsJson()),
                json.map(node.getOutputsJson()), node.getError(), node.getDurationMillis(), node.getStartedAt(), node.getFinishedAt());
    }

    private EventView eventView(RunEventEntity event) {
        return new EventView(event.getId(), event.getEventType(), event.getNodeId(), json.map(event.getDataJson()), event.getCreatedAt());
    }
}
