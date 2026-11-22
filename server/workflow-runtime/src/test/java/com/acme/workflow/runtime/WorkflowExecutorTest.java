package com.acme.workflow.runtime;

import com.acme.workflow.core.catalog.NodeCatalog;
import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphEdge;
import com.acme.workflow.core.model.GraphExamples;
import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowExecutorTest {
    private final WorkflowExecutor executor = new WorkflowExecutor(List.of(new EchoExecutor()), new NodeCatalog());

    private static final class EchoExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "echo";
        }

        @Override
        public Map<String, Object> execute(NodeExecutionContext context) {
            Object prefix = context.node().config().getOrDefault("prefix", "");
            Object subject = context.variables().containsKey("item")
                    ? context.variables().get("item") : context.variables().get("index");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("output", String.valueOf(prefix) + String.valueOf(subject));
            if (context.variables().get("index") != null) result.put("index", context.variables().get("index"));
            return result;
        }
    }

    @Test
    void aggregatesOutputsFromMultipleOutputNodes() {
        GraphNode input = input();
        GraphNode first = output("first", "message");
        GraphNode second = output("second", "copy");
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, first, second),
                List.of(edge("e1", input, first, null), edge("e2", first, second, null)), Map.of());

        RunResult result = executor.execute("run-output", graph, Map.of("text", "hello"), ExecutionObserver.NOOP);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outputs()).containsExactlyInAnyOrderEntriesOf(Map.of("message", "hello", "copy", "hello"));
    }

    @Test
    void selectsIfElifElseBranchAndMarksOtherBranchSkipped() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "count", "label", "Count", "type", "number", "required", true))));
        Map<String, Object> condition = Map.of("variable", "user-input.count", "operator", "greater", "value", 0);
        GraphNode decision = new GraphNode("if", "if-else", "Decision", new Position(100, 0),
                Map.of("cases", List.of(Map.of("id", "positive", "logicalOperator", "and", "conditions", List.of(condition)))));
        GraphNode positive = new GraphNode("positive-output", "output", "Positive", new Position(200, 0),
                Map.of("mappings", List.of(Map.of("name", "branch", "selector", "if.branch"))));
        GraphNode other = new GraphNode("else-output", "output", "Else", new Position(200, 100),
                Map.of("mappings", List.of(Map.of("name", "otherBranch", "selector", "if.branch"))));
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, decision, positive, other),
                List.of(edge("e1", input, decision, null), edge("e2", decision, positive, "positive"),
                        edge("e3", decision, other, "else")), Map.of());
        List<String> events = new ArrayList<>();
        Map<String, NodeStatus> statuses = new LinkedHashMap<>();
        ExecutionObserver observer = new ExecutionObserver() {
            @Override
            public void emit(String runId, String type, String nodeId, Map<String, Object> data) { events.add(type); }

            @Override
            public void nodeFinished(String runId, GraphNode node, NodeStatus status, Map<String, Object> output,
                                     String error, long durationMillis) { statuses.put(node.id(), status); }
        };

        RunResult result = executor.execute("run-if", graph, Map.of("count", 3), observer);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outputs()).containsExactlyEntriesOf(Map.of("branch", "positive"));
        assertThat(statuses).containsEntry("else-output", NodeStatus.SKIPPED);
        assertThat(events).contains("NODE_SKIPPED", "RUN_SUCCEEDED");
    }

    @Test
    void streamsAnswerAndReturnsCompleteText() {
        GraphDocument graph = GraphExamples.create(AppType.CHATFLOW);
        List<String> deltas = new ArrayList<>();
        ExecutionObserver observer = new ExecutionObserver() {
            @Override
            @SuppressWarnings("unchecked")
            public void emit(String runId, String type, String nodeId, Map<String, Object> data) {
                if ("ANSWER_DELTA".equals(type)) deltas.add((String) data.get("delta"));
            }
        };

        RunResult result = executor.execute("run-answer", graph, Map.of("text", "世界"), observer);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outputs()).containsEntry("answer", "你好，世界");
        assertThat(String.join("", deltas)).isEqualTo("你好，世界");
    }

    @Test
    void runsParallelBranchesAndJoinsOnlyAfterEveryBranchResolves() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "text", "label", "Text", "type", "text", "required", true))));
        GraphNode left = output("left", "left", "user-input.text");
        GraphNode right = output("right", "right", "user-input.text");
        GraphNode join = new GraphNode("join", "output", "Join", new Position(200, 50),
                Map.of("mappings", List.of(
                        Map.of("name", "joinedLeft", "selector", "left.left"),
                        Map.of("name", "joinedRight", "selector", "right.right"))));
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, left, right, join),
                List.of(edge("e1", input, left, null), edge("e2", input, right, null),
                        edge("e3", left, join, null), edge("e4", right, join, null)), Map.of());
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        Map<String, NodeStatus> statuses = new ConcurrentHashMap<>();
        ExecutionObserver observer = new ExecutionObserver() {
            @Override
            public void emit(String runId, String type, String nodeId, Map<String, Object> data) {
                if ("NODE_STARTED".equals(type)) trace.add("started:" + nodeId);
            }

            @Override
            public void nodeFinished(String runId, GraphNode node, NodeStatus status, Map<String, Object> output,
                                     String error, long durationMillis) {
                statuses.put(node.id(), status);
                trace.add("finished:" + node.id() + ":" + status);
            }
        };

        RunResult result = executor.execute("run-parallel", graph, Map.of("text", "hello"), observer);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outputs()).containsEntry("left", "hello").containsEntry("right", "hello")
                .containsEntry("joinedLeft", "hello").containsEntry("joinedRight", "hello");
        assertThat(statuses.values()).containsOnly(NodeStatus.SUCCEEDED);
        int joinStarted = trace.indexOf("started:join");
        assertThat(joinStarted).isGreaterThan(trace.indexOf("finished:left:SUCCEEDED"));
        assertThat(joinStarted).isGreaterThan(trace.indexOf("finished:right:SUCCEEDED"));
        assertThat(trace.stream().filter("started:join"::equals).count()).isEqualTo(1);
    }

    @Test
    void mergesSkippedBranchBeforeRunningJoin() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "count", "label", "Count", "type", "number", "required", true))));
        Map<String, Object> condition = Map.of("variable", "user-input.count", "operator", "greater", "value", 0);
        GraphNode decision = new GraphNode("if", "if-else", "Decision", new Position(100, 0),
                Map.of("cases", List.of(Map.of("id", "positive", "logicalOperator", "and", "conditions", List.of(condition)))));
        GraphNode left = output("left", "value", "user-input.count");
        GraphNode right = output("right", "otherValue", "user-input.count");
        GraphNode join = new GraphNode("join", "output", "Join", new Position(300, 50),
                Map.of("mappings", List.of(Map.of("name", "joined", "selector", "left.value"))));
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, decision, left, right, join),
                List.of(edge("e1", input, decision, null), edge("e2", decision, left, "positive"),
                        edge("e3", decision, right, "else"), edge("e4", left, join, null),
                        edge("e5", right, join, null)), Map.of());
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        Map<String, NodeStatus> statuses = new ConcurrentHashMap<>();
        ExecutionObserver observer = new ExecutionObserver() {
            @Override
            public void emit(String runId, String type, String nodeId, Map<String, Object> data) {
                if ("NODE_STARTED".equals(type)) trace.add("started:" + nodeId);
            }

            @Override
            public void nodeFinished(String runId, GraphNode node, NodeStatus status, Map<String, Object> output,
                                     String error, long durationMillis) {
                statuses.put(node.id(), status);
                trace.add("finished:" + node.id() + ":" + status);
            }
        };

        RunResult result = executor.execute("run-skip-merge", graph, Map.of("count", 3), observer);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outputs()).containsEntry("joined", 3);
        assertThat(statuses).containsEntry("right", NodeStatus.SKIPPED).containsEntry("join", NodeStatus.SUCCEEDED);
        assertThat(trace.indexOf("started:join")).isGreaterThan(trace.indexOf("finished:right:SKIPPED"));
        assertThat(trace.stream().filter("started:join"::equals).count()).isEqualTo(1);
    }

    @Test
    void runsIterationForEachItemAndCollectsOutputs() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "items", "label", "Items", "type", "json", "required", true))));
        GraphNode iteration = new GraphNode("iter", "iteration", "Iteration", new Position(100, 0),
                Map.of("inputSelector", "user-input.items", "outputSelector", "body.output", "parallel", false));
        GraphNode body = new GraphNode("body", "echo", "Body", new Position(200, 0), Map.of("prefix", "v-"), "iter");
        GraphNode result = output("result", "values", "iter.output");
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, iteration, body, result),
                List.of(edge("e1", input, iteration, null), edge("e2", iteration, result, null)), Map.of());

        RunResult run = executor.execute("run-iteration", graph, Map.of("items", List.of("a", "b", "c")),
                ExecutionObserver.NOOP);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.outputs()).containsEntry("values", List.of("v-a", "v-b", "v-c"));
    }

    @Test
    void runsParallelIterationAndKeepsInputOrder() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "items", "label", "Items", "type", "json", "required", true))));
        GraphNode iteration = new GraphNode("iter", "iteration", "Iteration", new Position(100, 0),
                Map.of("inputSelector", "user-input.items", "outputSelector", "body.output", "parallel", true,
                        "maxParallelism", 2));
        GraphNode body = new GraphNode("body", "echo", "Body", new Position(200, 0), Map.of("prefix", "v-"), "iter");
        GraphNode result = output("result", "values", "iter.output");
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, iteration, body, result),
                List.of(edge("e1", input, iteration, null), edge("e2", iteration, result, null)), Map.of());

        RunResult run = executor.execute("run-iteration-parallel", graph, Map.of("items", List.of("a", "b", "c")),
                ExecutionObserver.NOOP);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.outputs()).containsEntry("values", List.of("v-a", "v-b", "v-c"));
    }

    @Test
    void breaksLoopWhenExitLoopConditionMatches() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of()));
        GraphNode loop = new GraphNode("loop", "loop", "Loop", new Position(100, 0),
                Map.of("maxIterations", 5, "outputSelector", "body.output", "breakConditions", List.of()));
        GraphNode body = new GraphNode("body", "echo", "Body", new Position(200, 0), Map.of("prefix", "run-"), "loop");
        GraphNode exit = new GraphNode("exit", "exit-loop", "Exit", new Position(300, 0),
                Map.of("conditions", List.of(Map.of("variable", "index", "operator", "greater-or-equal", "value", 1))),
                "loop");
        GraphNode result = new GraphNode("result", "output", "Result", new Position(400, 0),
                Map.of("mappings", List.of(Map.of("name", "value", "selector", "loop.output"),
                        Map.of("name", "iterations", "selector", "loop.iterations"))));
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, loop, body, exit, result),
                List.of(edge("e1", input, loop, null), edge("e2", loop, result, null),
                        edge("e3", body, exit, null)), Map.of());

        RunResult run = executor.execute("run-loop", graph, Map.of(), ExecutionObserver.NOOP);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.outputs()).containsEntry("value", "run-1").containsEntry("iterations", 2);
    }

    @Test
    void pausesForHumanInputAndResumesWithSubmittedOutputs() {
        GraphNode input = input();
        GraphNode human = new GraphNode("human", "human-input", "Approval", new Position(100, 0),
                Map.of("prompt", "请处理 {{ user-input.text }}", "fields",
                        List.of(Map.of("variable", "note", "label", "处理意见", "type", "text", "required", true))));
        GraphNode output = output("result", "note", "human.note");
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, human, output),
                List.of(edge("e1", input, human, null), edge("e2", human, output, null)), Map.of());
        CompletableFuture<Map<String, Object>> submitted = new CompletableFuture<>();
        WorkflowExecutor blockingExecutor = new WorkflowExecutor(List.of(new EchoExecutor()), new NodeCatalog(),
                (runId, node) -> submitted.join());
        CompletableFuture<Void> waiting = new CompletableFuture<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        ExecutionObserver observer = new ExecutionObserver() {
            @Override
            public void nodeWaiting(String runId, GraphNode node, Map<String, Object> request) {
                waiting.complete(null);
            }

            @Override
            public void emit(String runId, String type, String nodeId, Map<String, Object> data) {
                events.add(type);
            }
        };

        CompletableFuture<RunResult> execution = CompletableFuture.supplyAsync(() ->
                blockingExecutor.execute("run-human", graph, Map.of("text", "hello"), observer));
        waiting.orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(execution).isNotDone();

        submitted.complete(Map.of("note", "通过"));
        RunResult result = execution.orTimeout(5, TimeUnit.SECONDS).join();

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outputs()).containsEntry("note", "通过");
        assertThat(events).contains("NODE_WAITING", "RUN_PAUSED", "NODE_RESUMED", "RUN_RESUMED", "RUN_SUCCEEDED");
    }

    @Test
    void reportsFailureThroughObserverWhenNoRunnableStartExists() {
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(), List.of(), Map.of());
        List<String> events = new ArrayList<>();
        List<RunResult> finished = new ArrayList<>();
        ExecutionObserver observer = new ExecutionObserver() {
            @Override
            public void emit(String runId, String type, String nodeId, Map<String, Object> data) { events.add(type); }

            @Override
            public void runFinished(String runId, RunResult result) { finished.add(result); }
        };

        RunResult result = executor.execute("run-invalid", graph, Map.of(), observer);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("user-input");
        assertThat(events).containsExactly("RUN_STARTED", "RUN_FAILED");
        assertThat(finished).containsExactly(result);
    }

    private static GraphNode input() {
        return GraphExamples.create(AppType.WORKFLOW).nodes().getFirst();
    }

    private static GraphNode output(String id, String name) {
        return output(id, name, "user-input.text");
    }

    private static GraphNode output(String id, String name, String selector) {
        return new GraphNode(id, "output", id, new Position(100, 0),
                Map.of("mappings", List.of(Map.of("name", name, "selector", selector))));
    }

    private static GraphEdge edge(String id, GraphNode source, GraphNode target, String handle) {
        return new GraphEdge(id, source.id(), target.id(), handle, null);
    }
}
