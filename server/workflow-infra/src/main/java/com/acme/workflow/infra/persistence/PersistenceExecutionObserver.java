package com.acme.workflow.infra.persistence;

import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.runtime.ExecutionObserver;
import com.acme.workflow.runtime.NodeStatus;
import com.acme.workflow.runtime.RunResult;
import com.acme.workflow.runtime.RunStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Component
public class PersistenceExecutionObserver implements ExecutionObserver {
    private final RunRepository runs;
    private final NodeRunRepository nodeRuns;
    private final RunEventRepository events;
    private final JsonCodec json;

    public PersistenceExecutionObserver(RunRepository runs, NodeRunRepository nodeRuns, RunEventRepository events, JsonCodec json) {
        this.runs = runs; this.nodeRuns = nodeRuns; this.events = events; this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emit(String runId, String type, String nodeId, Map<String, Object> data) {
        if ("RUN_STARTED".equals(type)) {
            RunEntity run = runs.findById(runId).orElseThrow();
            run.start(Instant.now());
            runs.save(run);
        }
        events.save(new RunEventEntity(runId, type, nodeId, json.write(data), Instant.now()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void nodeStarted(String runId, GraphNode node, Map<String, Object> input) {
        nodeRuns.save(new NodeRunEntity(runId, node.id(), node.type(), json.write(input), Instant.now()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void nodeFinished(String runId, GraphNode node, NodeStatus status, Map<String, Object> output,
                             String error, long durationMillis) {
        NodeRunEntity entity = nodeRuns.findByRunIdAndNodeId(runId, node.id()).orElseGet(() -> {
            NodeRunEntity created = new NodeRunEntity(runId, node.id(), node.type(), "{}", Instant.now());
            return nodeRuns.save(created);
        });
        entity.finish(status, json.write(output), error, durationMillis, Instant.now());
        nodeRuns.save(entity);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void nodeWaiting(String runId, GraphNode node, Map<String, Object> request) {
        NodeRunEntity entity = nodeRuns.findByRunIdAndNodeId(runId, node.id()).orElseGet(() ->
                nodeRuns.save(new NodeRunEntity(runId, node.id(), node.type(), "{}", Instant.now())));
        entity.waitForInput(json.write(request));
        nodeRuns.save(entity);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void nodeResumed(String runId, GraphNode node) {
        nodeRuns.findByRunIdAndNodeId(runId, node.id()).ifPresent(entity -> {
            entity.resume();
            nodeRuns.save(entity);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runPaused(String runId, GraphNode node) {
        RunEntity run = runs.findById(runId).orElseThrow();
        run.pause();
        runs.save(run);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runResumed(String runId, GraphNode node, int remainingWaiters) {
        if (remainingWaiters > 0) return;
        RunEntity run = runs.findById(runId).orElseThrow();
        run.resume();
        runs.save(run);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runFinished(String runId, RunResult result) {
        RunEntity run = runs.findById(runId).orElseThrow();
        if (run.getStartedAt() == null) run.start(Instant.now());
        run.finish(result.status(), json.write(result.outputs()), result.error(), Instant.now());
        runs.save(run);
    }
}
