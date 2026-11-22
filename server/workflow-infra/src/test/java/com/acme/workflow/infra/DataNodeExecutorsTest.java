package com.acme.workflow.infra;

import com.acme.workflow.core.catalog.NodeCatalog;
import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphEdge;
import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.Position;
import com.acme.workflow.infra.execution.ListOperatorExecutor;
import com.acme.workflow.infra.execution.TemplateTransformExecutor;
import com.acme.workflow.infra.execution.VariableAggregatorExecutor;
import com.acme.workflow.infra.execution.VariableAssignerExecutor;
import com.acme.workflow.runtime.ExecutionObserver;
import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.RunResult;
import com.acme.workflow.runtime.RunStatus;
import com.acme.workflow.runtime.WorkflowExecutor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataNodeExecutorsTest {
    private final TemplateTransformExecutor template = new TemplateTransformExecutor();
    private final VariableAggregatorExecutor aggregator = new VariableAggregatorExecutor();
    private final VariableAssignerExecutor assigner = new VariableAssignerExecutor();
    private final ListOperatorExecutor listOperator = new ListOperatorExecutor();

    @Test
    void templateRendersRunVariablesAndNodeSelectors() {
        GraphNode node = node("template", "template", Map.of("template", "{{greeting}} {{start.text}}!"));
        Map<String, Map<String, Object>> outputs = Map.of("start", Map.of("text", "hello"));

        Map<String, Object> result = template.execute(context(node, outputs, Map.of("greeting", "Hi")));

        assertThat(result).containsEntry("output", "Hi hello!");
    }

    @Test
    void templateResolvesDeclaredVariableNames() {
        GraphNode node = node("template", "template", Map.of(
                "template", "{{name}}/{{start.text}}",
                "variables", List.of(Map.of("variable", "name", "selector", "start.text"))));

        Map<String, Object> result = template.execute(context(node, Map.of("start", Map.of("text", "hi")), Map.of()));

        assertThat(result).containsEntry("output", "hi/hi");
    }

    @Test
    void aggregatorReturnsFirstNonEmptyValue() {
        GraphNode node = node("aggregator", "variable-aggregator", Map.of("variables", List.of(
                Map.of("selector", "first.text"), Map.of("selector", "second.text"))));
        Map<String, Map<String, Object>> outputs = Map.of(
                "first", Map.of("text", ""), "second", Map.of("text", "second"));

        assertThat(aggregator.execute(context(node, outputs, Map.of()))).containsEntry("output", "second");
    }

    @Test
    void aggregatorFallsBackToEmptyStringWhenAllValuesEmpty() {
        GraphNode node = node("aggregator", "variable-aggregator", Map.of("variables", List.of(
                Map.of("selector", "first.text"))));

        assertThat(aggregator.execute(context(node, Map.of("first", Map.of("text", "")), Map.of())))
                .containsEntry("output", "");
    }

    @Test
    void assignerWritesRunVariableUsedByDownstreamTemplate() {
        GraphNode input = node("user-input", "user-input", Map.of(
                "fields", List.of(Map.of("variable", "text", "label", "Text", "type", "text", "required", true))));
        GraphNode assignment = node("assignment", "variable-assigner", Map.of("variable", "copy", "value", "{{user-input.text}}"));
        GraphNode render = node("render", "template", Map.of("template", "{{copy}}-{{user-input.text}}"));
        GraphNode output = node("end", "output", Map.of("mappings",
                List.of(Map.of("name", "message", "selector", "render.output"))));
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW,
                List.of(input, assignment, render, output),
                List.of(edge("e1", "user-input", "assignment"), edge("e2", "assignment", "render"),
                        edge("e3", "render", "end")), Map.of());
        WorkflowExecutor executor = new WorkflowExecutor(
                List.of(new VariableAssignerExecutor(), new TemplateTransformExecutor()), new NodeCatalog());

        RunResult result = executor.execute("run-data", graph, Map.of("text", "hi"), ExecutionObserver.NOOP);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outputs()).containsExactlyEntriesOf(Map.of("message", "hi-hi"));
    }

    @Test
    void listOperatorFiltersSortsAndLimits() {
        GraphNode node = node("list", "list-operator", Map.of(
                "inputSelector", "start.items", "filterOperator", "equals", "filterKey", "status", "filterValue", "ok",
                "sortEnabled", true, "sortKey", "score", "sortOrder", "desc", "limit", 2));
        Map<String, Map<String, Object>> outputs = Map.of("start", Map.of("items", List.of(
                Map.of("status", "ok", "score", 1), Map.of("status", "bad", "score", 9),
                Map.of("status", "ok", "score", 5), Map.of("status", "ok", "score", 3))));

        Object result = listOperator.execute(context(node, outputs, Map.of())).get("output");

        assertThat(result).isEqualTo(List.of(Map.of("status", "ok", "score", 5), Map.of("status", "ok", "score", 3)));
    }

    @Test
    void listOperatorExtractsOneBasedSerial() {
        GraphNode node = node("list", "list-operator", Map.of("inputSelector", "start.items", "extractSerial", 2));
        Map<String, Map<String, Object>> outputs = Map.of("start", Map.of("items", List.of("a", "b", "c")));

        assertThat(listOperator.execute(context(node, outputs, Map.of()))).containsEntry("output", "b");
    }

    @Test
    void listOperatorSortsScalarsAscending() {
        GraphNode node = node("list", "list-operator", Map.of(
                "inputSelector", "start.items", "sortEnabled", true, "sortOrder", "asc"));
        Map<String, Map<String, Object>> outputs = Map.of("start", Map.of("items", List.of(3, 1, 2)));

        assertThat(listOperator.execute(context(node, outputs, Map.of())).get("output")).isEqualTo(List.of(1, 2, 3));
    }

    @Test
    void listOperatorRejectsNonListInput() {
        GraphNode node = node("list", "list-operator", Map.of("inputSelector", "start.text"));

        assertThatThrownBy(() -> listOperator.execute(context(node, Map.of("start", Map.of("text", "nope")), Map.of())))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("必须指向数组");
    }

    private static NodeExecutionContext context(GraphNode node, Map<String, Map<String, Object>> outputs,
                                                Map<String, Object> variables) {
        return new NodeExecutionContext("run", null, node, Map.of(), outputs,
                new LinkedHashMap<>(variables), (type, data) -> { });
    }

    private static GraphNode node(String id, String type, Map<String, Object> config) {
        return new GraphNode(id, type, id, new Position(0, 0), config);
    }

    private static GraphEdge edge(String id, String source, String target) {
        return new GraphEdge(id, source, target, null, null);
    }
}
