package com.acme.workflow.runtime;

import com.acme.workflow.core.catalog.NodeCatalog;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphEdge;
import com.acme.workflow.core.model.GraphNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;
import java.util.stream.Collectors;

public class WorkflowExecutor {
    private final Map<String, NodeExecutor> executors;
    private final NodeCatalog catalog;
    private final HumanInputGateway humanInputs;

    public WorkflowExecutor(Collection<NodeExecutor> executors, NodeCatalog catalog) {
        this(executors, catalog, HumanInputGateway.UNSUPPORTED);
    }

    public WorkflowExecutor(Collection<NodeExecutor> executors, NodeCatalog catalog, HumanInputGateway humanInputs) {
        this.executors = executors.stream().collect(Collectors.toUnmodifiableMap(NodeExecutor::type, Function.identity()));
        this.catalog = catalog;
        this.humanInputs = humanInputs;
    }

    public RunResult execute(String runId, GraphDocument graph, Map<String, Object> inputs, ExecutionObserver observer) {
        Map<String, Object> safeInputs = inputs == null ? Map.of() : inputs;
        return new Execution(runId, graph, safeInputs, observer).run();
    }

    private final class Execution {
        private final String runId;
        private final GraphDocument graph;
        private final Map<String, Object> inputs;
        private final ExecutionObserver observer;
        private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
        private final Map<String, List<GraphEdge>> outgoing = new HashMap<>();
        private final Map<String, List<GraphEdge>> incoming = new HashMap<>();
        private final Map<String, Map<String, Object>> rootOutputs = new ConcurrentHashMap<>();
        private final Map<String, Object> rootVariables = Collections.synchronizedMap(new LinkedHashMap<>());
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger waitingCount = new AtomicInteger();
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final Object eventLock = new Object();

        private Execution(String runId, GraphDocument graph, Map<String, Object> inputs, ExecutionObserver observer) {
            this.runId = runId;
            this.graph = graph;
            this.inputs = inputs;
            this.observer = observer;
            for (GraphNode node : graph.nodes()) {
                if (node != null) nodes.put(node.id(), node);
            }
            for (GraphEdge edge : graph.edges()) {
                if (edge == null) continue;
                outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
                incoming.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
            }
        }

        private RunResult run() {
            emit(null, "RUN_STARTED", Map.of("appType", graph.appType().name(), "inputs", inputs));
            ScopeRunner root = null;
            try {
                GraphNode start = nodes.values().stream()
                        .filter(node -> node.parentId() == null && "user-input".equals(node.type()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("运行需要 user-input 开始节点。"));
                root = new ScopeRunner(null, start, rootOutputs, rootVariables, false);
                root.run();
                Throwable error = failure.get();
                if (error != null) {
                    if (error instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException(error.getMessage(), error);
                }
                Map<String, Object> finalOutputs = root.collectFinalOutputs();
                RunResult result = new RunResult(RunStatus.SUCCEEDED, finalOutputs, null);
                emit(null, "RUN_SUCCEEDED", Map.of("outputs", finalOutputs));
                observer.runFinished(runId, result);
                return result;
            } catch (Throwable error) {
                String message = error.getMessage() == null ? "运行失败。" : error.getMessage();
                Map<String, Object> finalOutputs = root == null ? Map.of() : root.collectFinalOutputs();
                RunResult result = new RunResult(RunStatus.FAILED, finalOutputs, message);
                emit(null, "RUN_FAILED", Map.of("error", message));
                observer.runFinished(runId, result);
                return result;
            } finally {
                workers.close();
            }
        }

        private final class ScopeRunner {
            private final String scopeId;
            private final List<GraphNode> scopeNodes = new ArrayList<>();
            private final Map<String, GraphNode> localNodes = new LinkedHashMap<>();
            private final Map<String, List<GraphEdge>> localOutgoing = new HashMap<>();
            private final Map<String, List<GraphEdge>> localIncoming = new HashMap<>();
            private final Map<String, Map<String, Object>> outputs;
            private final Map<String, Object> variables;
            private final boolean stopOnExit;
            private final List<GraphNode> starts;
            private final Phaser phaser = new Phaser(1);
            private final Set<String> executed = ConcurrentHashMap.newKeySet();
            private final Set<String> resolved = ConcurrentHashMap.newKeySet();
            private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
            private final List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
            private volatile boolean breakRequested;

            private ScopeRunner(String scopeId, GraphNode rootStart, Map<String, Map<String, Object>> outputs,
                                Map<String, Object> variables, boolean stopOnExit) {
                this.scopeId = scopeId;
                this.outputs = outputs;
                this.variables = variables;
                this.stopOnExit = stopOnExit;
                for (GraphNode node : graph.nodes()) {
                    if (Objects.equals(node.parentId(), scopeId)) {
                        scopeNodes.add(node);
                        localNodes.put(node.id(), node);
                    }
                }
                for (GraphEdge edge : graph.edges()) {
                    if (localNodes.containsKey(edge.source()) && localNodes.containsKey(edge.target())) {
                        localOutgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
                        localIncoming.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
                    }
                }
                if (scopeId == null && rootStart != null) {
                    starts = List.of(rootStart);
                } else {
                    starts = scopeNodes.stream()
                            .filter(node -> localIncoming.getOrDefault(node.id(), List.of()).isEmpty())
                            .toList();
                }
            }

            private void run() {
                if (failure.get() != null) return;
                if (starts.isEmpty()) {
                    failure.compareAndSet(null, new NodeExecutionException(
                            scopeId == null ? "graph" : scopeId, "作用域缺少入口节点。"));
                    return;
                }
                for (GraphNode start : starts) schedule(start);
                phaser.arriveAndAwaitAdvance();
                if (failure.get() == null) markUnresolvedAsSkipped();
            }

            private void schedule(GraphNode node) {
                if (!scheduled.add(node.id())) return;
                submit(node, true);
            }

            private void trySchedule(GraphNode node) {
                if (node == null || scheduled.contains(node.id()) || failure.get() != null) return;
                List<GraphEdge> edges = localIncoming.getOrDefault(node.id(), List.of());
                for (GraphEdge edge : edges) {
                    if (!resolved.contains(edge.source())) return;
                }
                if (!scheduled.add(node.id())) return;
                submit(node, edges.stream().anyMatch(this::isActive));
            }

            private void submit(GraphNode node, boolean active) {
                phaser.register();
                workers.execute(() -> {
                    try {
                        if (failure.get() == null) {
                            if (active) execute(node);
                            else skip(node, "未命中的分支");
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, normalize(node, error));
                    } finally {
                        phaser.arriveAndDeregister();
                    }
                });
            }

            private void execute(GraphNode node) {
                long started = System.nanoTime();
                emitStarted(node);
                Map<String, Object> output;
                try {
                    output = isContainer(node)
                            ? executeContainer(node, outputs, variables)
                            : executeNode(node, outputs, variables);
                } catch (Throwable error) {
                    long duration = (System.nanoTime() - started) / 1_000_000;
                    String message = message(error);
                    emitFinished(node, NodeStatus.FAILED, Map.of(), message, duration, "NODE_FAILED",
                            Map.of("error", message, "durationMillis", duration));
                    failure.compareAndSet(null, normalize(node, error));
                    return;
                }
                outputs.put(node.id(), output);
                executed.add(node.id());
                executionOrder.add(node.id());
                long duration = (System.nanoTime() - started) / 1_000_000;
                emitFinished(node, NodeStatus.SUCCEEDED, output, null, duration, "NODE_SUCCEEDED",
                        Map.of("outputs", output, "durationMillis", duration));
                resolved.add(node.id());
                if ("exit-loop".equals(node.type()) && stopOnExit && Boolean.TRUE.equals(output.get("break"))) {
                    breakRequested = true;
                    return;
                }
                for (GraphEdge edge : localOutgoing.getOrDefault(node.id(), List.of())) {
                    trySchedule(localNodes.get(edge.target()));
                }
            }

            private void skip(GraphNode node, String reason) {
                emitFinished(node, NodeStatus.SKIPPED, Map.of(), reason, 0, "NODE_SKIPPED", Map.of("reason", reason));
                resolved.add(node.id());
                if (isContainer(node)) markDescendantsSkipped(node.id(), reason);
                for (GraphEdge edge : localOutgoing.getOrDefault(node.id(), List.of())) {
                    trySchedule(localNodes.get(edge.target()));
                }
            }

            private void markUnresolvedAsSkipped() {
                for (GraphNode node : scopeNodes) {
                    if (resolved.contains(node.id())) continue;
                    resolved.add(node.id());
                    emitFinished(node, NodeStatus.SKIPPED, Map.of(), "未命中的分支", 0, "NODE_SKIPPED",
                            Map.of("reason", "未命中的分支"));
                    if (isContainer(node)) markDescendantsSkipped(node.id(), "未命中的分支");
                }
            }

            private void markDescendantsSkipped(String containerId, String reason) {
                for (GraphNode child : nodes.values()) {
                    if (!containerId.equals(child.parentId())) continue;
                    if (resolved.add(child.id())) {
                        emitFinished(child, NodeStatus.SKIPPED, Map.of(), reason, 0, "NODE_SKIPPED",
                                Map.of("reason", reason));
                    }
                    if (isContainer(child)) markDescendantsSkipped(child.id(), reason);
                }
            }

            private boolean isActive(GraphEdge edge) {
                if (!executed.contains(edge.source())) return false;
                GraphNode source = localNodes.get(edge.source());
                if (source == null || !"if-else".equals(source.type())) return true;
                Map<String, Object> output = outputs.get(source.id());
                Object branch = output == null ? null : output.get("branch");
                return branch != null && String.valueOf(branch).equals(edge.sourceHandle());
            }

            private Map<String, Object> collectFinalOutputs() {
                Map<String, Object> result = new LinkedHashMap<>();
                for (GraphNode node : scopeNodes) {
                    if (executed.contains(node.id()) && ("output".equals(node.type()) || "answer".equals(node.type()))) {
                        result.putAll(outputs.getOrDefault(node.id(), Map.of()));
                    }
                }
                return result;
            }

            private Object lastOutput() {
                for (int i = executionOrder.size() - 1; i >= 0; i--) {
                    String id = executionOrder.get(i);
                    GraphNode node = localNodes.get(id);
                    if (node != null && "exit-loop".equals(node.type())) continue;
                    Map<String, Object> output = outputs.get(id);
                    if (output == null) continue;
                    if (output.containsKey("output")) return output.get("output");
                    if (output.size() == 1) return output.values().iterator().next();
                    return output;
                }
                return null;
            }
        }

        private Map<String, Object> executeContainer(GraphNode node, Map<String, Map<String, Object>> scopeOutputs,
                                                     Map<String, Object> scopeVariables) {
            return switch (node.type()) {
                case "iteration" -> executeIteration(node, scopeOutputs, scopeVariables);
                case "loop" -> executeLoop(node, scopeOutputs, scopeVariables);
                default -> throw new NodeExecutionException(node.id(), "未知容器节点：" + node.type());
            };
        }

        private Map<String, Object> executeIteration(GraphNode node, Map<String, Map<String, Object>> scopeOutputs,
                                                     Map<String, Object> scopeVariables) {
            Object raw = Variables.select(string(node.config().get("inputSelector")), scopeOutputs, scopeVariables);
            if (!(raw instanceof List<?> items)) throw new NodeExecutionException(node.id(), "迭代输入必须是列表。");
            boolean parallel = Boolean.TRUE.equals(node.config().get("parallel"));
            int permits = Math.max(1, Math.min(32, intValue(node.config().get("maxParallelism"), 4)));
            AtomicReferenceArray<Object> collected = new AtomicReferenceArray<>(items.size());
            Semaphore semaphore = new Semaphore(permits);
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                int itemIndex = index;
                Runnable task = () -> {
                    if (failure.get() != null) return;
                    try {
                        if (parallel) semaphore.acquire();
                        try {
                            Map<String, Map<String, Object>> itemOutputs = new ConcurrentHashMap<>(scopeOutputs);
                            Map<String, Object> itemVariables = copyVariables(scopeVariables);
                            itemVariables.put("item", items.get(itemIndex));
                            itemVariables.put("index", itemIndex);
                            emit(node.id(), "ITERATION_ITEM_STARTED", Map.of("index", itemIndex, "total", items.size()));
                            ScopeRunner child = new ScopeRunner(node.id(), null, itemOutputs, itemVariables, false);
                            child.run();
                            if (failure.get() != null) return;
                            collected.set(itemIndex, selectOutput(node, child, itemOutputs, itemVariables));
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("index", itemIndex);
                            data.put("output", collected.get(itemIndex));
                            emit(node.id(), "ITERATION_ITEM_SUCCEEDED", data);
                        } finally {
                            if (parallel) semaphore.release();
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        failure.compareAndSet(null, new NodeExecutionException(node.id(), "迭代执行被中断。", error));
                    } catch (Throwable error) {
                        failure.compareAndSet(null, normalize(node, error));
                    }
                };
                if (parallel) futures.add(workers.submit(task));
                else task.run();
                if (failure.get() != null) break;
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, new NodeExecutionException(node.id(), "迭代执行被中断。", error));
                    break;
                } catch (Exception error) {
                    failure.compareAndSet(null, normalize(node, error));
                    break;
                }
            }
            if (failure.get() != null) throw new NodeExecutionException(node.id(), message(failure.get()));
            List<Object> results = new ArrayList<>(items.size());
            for (int index = 0; index < items.size(); index++) results.add(collected.get(index));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("output", results);
            output.put("count", results.size());
            return output;
        }

        private Map<String, Object> executeLoop(GraphNode node, Map<String, Map<String, Object>> scopeOutputs,
                                                 Map<String, Object> scopeVariables) {
            int max = Math.max(1, Math.min(100, intValue(node.config().get("maxIterations"), 10)));
            Map<String, Map<String, Object>> loopOutputs = new ConcurrentHashMap<>(scopeOutputs);
            Map<String, Object> loopVariables = copyVariables(scopeVariables);
            Object[] lastOutput = new Object[1];
            int iterations = 0;
            List<?> breakConditions = list(node.config().get("breakConditions"));
            String breakOperator = string(node.config().get("breakLogicalOperator"));
            for (int index = 0; index < max; index++) {
                if (failure.get() != null) break;
                loopVariables.put("index", index);
                iterations = index + 1;
                emit(node.id(), "LOOP_ITERATION_STARTED", Map.of("index", index, "max", max));
                ScopeRunner child = new ScopeRunner(node.id(), null, loopOutputs, loopVariables, true);
                child.run();
                if (failure.get() != null) break;
                lastOutput[0] = selectOutput(node, child, loopOutputs, loopVariables);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("index", index);
                data.put("output", lastOutput[0]);
                emit(node.id(), "LOOP_ITERATION_SUCCEEDED", data);
                if (child.breakRequested) break;
                if (!breakConditions.isEmpty()
                        && ConditionEvaluator.evaluateAll(breakConditions, breakOperator, loopOutputs, loopVariables)) break;
            }
            if (failure.get() != null) throw new NodeExecutionException(node.id(), message(failure.get()));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("output", lastOutput[0]);
            output.put("iterations", iterations);
            return output;
        }

        private Object selectOutput(GraphNode node, ScopeRunner child, Map<String, Map<String, Object>> scopeOutputs,
                                    Map<String, Object> scopeVariables) {
            String selector = string(node.config().get("outputSelector"));
            return selector.isBlank() ? child.lastOutput() : Variables.select(selector, scopeOutputs, scopeVariables);
        }

        private Map<String, Object> executeNode(GraphNode node, Map<String, Map<String, Object>> scopeOutputs,
                                                Map<String, Object> scopeVariables) {
            NodeExecutionContext context = new NodeExecutionContext(runId, graph, node, inputs, scopeOutputs, scopeVariables,
                    (type, data) -> emit(node.id(), type, data));
            return switch (node.type()) {
                case "user-input" -> validateInputs(node, inputs);
                case "if-else" -> Map.of("branch", chooseBranch(node, scopeOutputs, scopeVariables));
                case "output" -> executeOutput(node, scopeOutputs, scopeVariables);
                case "answer" -> executeAnswer(node, context);
                case "human-input" -> executeHumanInput(node, context);
                case "exit-loop" -> executeExitLoop(node, scopeOutputs, scopeVariables);
                case "iteration", "loop" -> throw new NodeExecutionException(node.id(), "容器节点必须由作用域调度器执行。");
                default -> {
                    NodeExecutor executor = executors.get(node.type());
                    if (executor == null) {
                        String support = catalog.find(node.type()).map(item -> item.supportMessage()).orElse("未知节点类型。");
                        throw new NodeExecutionException(node.id(), "节点 " + node.title() + " 无法运行：" + support);
                    }
                    Map<String, Object> result = executor.execute(context);
                    yield result == null ? Map.of() : result;
                }
            };
        }

        private Map<String, Object> executeExitLoop(GraphNode node, Map<String, Map<String, Object>> scopeOutputs,
                                                    Map<String, Object> scopeVariables) {
            List<?> conditions = list(node.config().get("conditions"));
            boolean shouldBreak = conditions.isEmpty() || ConditionEvaluator.evaluateAll(conditions,
                    string(node.config().get("logicalOperator")), scopeOutputs, scopeVariables);
            return Map.of("break", shouldBreak);
        }

        private String chooseBranch(GraphNode node, Map<String, Map<String, Object>> outputs,
                                    Map<String, Object> variables) {
            Object rawCases = node.config().get("cases");
            if (!(rawCases instanceof List<?> cases)) throw new NodeExecutionException(node.id(), "cases 配置无效。");
            for (Object raw : cases) {
                if (raw instanceof Map<?, ?> oneCase
                        && ConditionEvaluator.evaluateCase(oneCase, outputs, variables)) return String.valueOf(oneCase.get("id"));
            }
            return "else";
        }

        private Map<String, Object> validateInputs(GraphNode node, Map<String, Object> inputs) {
            return FormInputValidator.validate(node, inputs);
        }

        private Map<String, Object> executeHumanInput(GraphNode node, NodeExecutionContext context) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("prompt", context.render(String.valueOf(node.config().getOrDefault("prompt", ""))));
            request.put("fields", node.config().getOrDefault("fields", List.of()));
            humanInputs.prepare(runId, node);
            waitingCount.incrementAndGet();
            synchronized (eventLock) {
                observer.nodeWaiting(runId, node, request);
                observer.runPaused(runId, node);
                observer.emit(runId, "NODE_WAITING", node.id(), request);
                observer.emit(runId, "RUN_PAUSED", node.id(), Map.of("title", node.title()));
            }
            Map<String, Object> submitted;
            try {
                submitted = humanInputs.awaitInput(runId, node);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new NodeExecutionException(node.id(), "人工输入等待被中断。", error);
            } finally {
                int remaining = waitingCount.decrementAndGet();
                synchronized (eventLock) {
                    observer.nodeResumed(runId, node);
                    observer.runResumed(runId, node, remaining);
                    observer.emit(runId, "NODE_RESUMED", node.id(), Map.of());
                    observer.emit(runId, "RUN_RESUMED", node.id(), Map.of("remainingWaiters", remaining));
                }
            }
            return FormInputValidator.validate(node, submitted);
        }

        private Map<String, Object> executeOutput(GraphNode node, Map<String, Map<String, Object>> outputs,
                                                  Map<String, Object> variables) {
            Object rawMappings = node.config().get("mappings");
            if (!(rawMappings instanceof List<?> mappings)) throw new NodeExecutionException(node.id(), "mappings 配置无效。");
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object raw : mappings) {
                if (!(raw instanceof Map<?, ?> mapping)) throw new NodeExecutionException(node.id(), "输出映射必须为对象。");
                result.put(String.valueOf(mapping.get("name")),
                        Variables.select(String.valueOf(mapping.get("selector")), outputs, variables));
            }
            return result;
        }

        private Map<String, Object> executeAnswer(GraphNode node, NodeExecutionContext context) {
            String answer = context.render(String.valueOf(node.config().getOrDefault("answer", "")));
            int offset = 0;
            while (offset < answer.length()) {
                int next = Math.min(answer.length(), offset + 24);
                if (next < answer.length() && Character.isHighSurrogate(answer.charAt(next - 1))) next--;
                context.emit("ANSWER_DELTA", Map.of("delta", answer.substring(offset, next)));
                offset = next;
            }
            return Map.of("answer", answer);
        }

        private Map<String, Object> copyVariables(Map<String, Object> source) {
            synchronized (source) {
                return Collections.synchronizedMap(new LinkedHashMap<>(source));
            }
        }

        private void emitStarted(GraphNode node) {
            synchronized (eventLock) {
                observer.nodeStarted(runId, node, Map.of());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("nodeType", node.type());
                data.put("title", node.title());
                if (node.parentId() != null) data.put("parentId", node.parentId());
                observer.emit(runId, "NODE_STARTED", node.id(), data);
            }
        }

        private void emitFinished(GraphNode node, NodeStatus status, Map<String, Object> output, String error,
                                  long durationMillis, String eventType, Map<String, Object> eventData) {
            synchronized (eventLock) {
                observer.nodeFinished(runId, node, status, output, error, durationMillis);
                observer.emit(runId, eventType, node.id(), eventData);
            }
        }

        private void emit(String nodeId, String type, Map<String, Object> data) {
            synchronized (eventLock) {
                observer.emit(runId, type, nodeId, data);
            }
        }

        private boolean isContainer(GraphNode node) {
            return "iteration".equals(node.type()) || "loop".equals(node.type());
        }

        private Throwable normalize(GraphNode node, Throwable error) {
            if (error instanceof NodeExecutionException) return error;
            return new NodeExecutionException(node.id(), message(error), error);
        }

        private String message(Throwable error) {
            return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }

        private String string(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private int intValue(Object value, int fallback) {
            return value instanceof Number number ? number.intValue() : fallback;
        }

        private List<?> list(Object value) {
            return value instanceof List<?> items ? items : List.of();
        }
    }
}
