package com.acme.workflow.core;

import com.acme.workflow.core.catalog.NodeCatalog;
import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphExamples;
import com.acme.workflow.core.model.GraphEdge;
import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.Position;
import com.acme.workflow.core.model.ValidationIssue;
import com.acme.workflow.core.validation.GraphValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphValidatorTest {
    private final GraphValidator validator = new GraphValidator(new NodeCatalog());

    @Test
    void acceptsBothCoreExamples() {
        assertThat(validator.validate(GraphExamples.create(AppType.WORKFLOW)).issues()).isEmpty();
        assertThat(validator.validate(GraphExamples.create(AppType.CHATFLOW)).issues()).isEmpty();
    }

    @Test
    void reportsUnknownVariablesAndUnsupportedNodesWithPaths() {
        GraphDocument example = GraphExamples.create(AppType.WORKFLOW);
        GraphNode output = new GraphNode("output", "output", "Output", example.nodes().get(1).position(),
                Map.of("mappings", List.of(Map.of("name", "message", "selector", "missing.text"))));
        GraphDocument unknownVariable = new GraphDocument(1, AppType.WORKFLOW,
                List.of(example.nodes().getFirst(), output), example.edges(), example.viewport());

        assertThat(validator.validate(unknownVariable).issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo("UNKNOWN_VARIABLE");
                    assertThat(issue.path()).contains("$.nodes[id=output].config.mappings[0].selector");
                });

        GraphNode unsupportedNode = new GraphNode("tool", "tool", "Tool", output.position(), Map.of());
        GraphDocument unsupported = new GraphDocument(1, AppType.WORKFLOW,
                List.of(example.nodes().getFirst(), unsupportedNode), example.edges(), example.viewport());
        assertThat(validator.validate(unsupported).issues())
                .extracting(ValidationIssue::code).contains("UNSUPPORTED_NODE");

        GraphNode agent = new GraphNode("agent", "agent", "Agent", output.position(), Map.of("strategy", "react"));
        GraphDocument badAgent = new GraphDocument(1, AppType.WORKFLOW,
                List.of(example.nodes().getFirst(), agent), example.edges(), example.viewport());
        assertThat(validator.validate(badAgent).issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo("STRATEGY");
                    assertThat(issue.path()).contains("config.strategy");
                });
    }

    @Test
    void acceptsParallelFanOutAndJoin() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "text", "label", "Text", "type", "text"))));
        GraphNode left = new GraphNode("left", "output", "Left", new Position(100, 0),
                Map.of("mappings", List.of(Map.of("name", "left", "selector", "user-input.text"))));
        GraphNode right = new GraphNode("right", "output", "Right", new Position(100, 100),
                Map.of("mappings", List.of(Map.of("name", "right", "selector", "user-input.text"))));
        GraphNode join = new GraphNode("join", "output", "Join", new Position(200, 50),
                Map.of("mappings", List.of(
                        Map.of("name", "joinedLeft", "selector", "left.left"),
                        Map.of("name", "joinedRight", "selector", "right.right"))));
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, left, right, join),
                List.of(new GraphEdge("e1", "user-input", "left", null, null),
                        new GraphEdge("e2", "user-input", "right", null, null),
                        new GraphEdge("e3", "left", "join", null, null),
                        new GraphEdge("e4", "right", "join", null, null)), Map.of());

        assertThat(validator.validate(graph).issues()).isEmpty();
    }

    @Test
    void acceptsIterationScopeWithInternalBody() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "items", "label", "Items", "type", "json"))));
        GraphNode iteration = new GraphNode("iteration", "iteration", "Iteration", new Position(100, 0),
                Map.of("inputSelector", "user-input.items", "outputSelector", "body.output", "parallel", false), null);
        GraphNode body = new GraphNode("body", "template", "Body", new Position(120, 0),
                Map.of("template", "item"), "iteration");
        GraphNode output = new GraphNode("output", "output", "Output", new Position(300, 0),
                Map.of("mappings", List.of(Map.of("name", "list", "selector", "iteration.output"))), null);
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, iteration, body, output),
                List.of(new GraphEdge("e1", "user-input", "iteration", null, null),
                        new GraphEdge("e2", "iteration", "output", null, null)), Map.of());

        assertThat(validator.validate(graph).issues()).isEmpty();
    }

    @Test
    void acceptsLoopScopeWithExitLoopNode() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "text", "label", "Text", "type", "text"))));
        GraphNode loop = new GraphNode("loop", "loop", "Loop", new Position(100, 0),
                Map.of("maxIterations", 3, "outputSelector", "body.output"), null);
        GraphNode body = new GraphNode("body", "template", "Body", new Position(120, 0),
                Map.of("template", "{{user-input.text}}"), "loop");
        GraphNode exit = new GraphNode("exit-loop", "exit-loop", "Exit", new Position(200, 0),
                Map.of("conditions", List.of(Map.of("variable", "body.output", "operator", "equals", "value", "stop"))), "loop");
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, loop, body, exit),
                List.of(new GraphEdge("e1", "user-input", "loop", null, null),
                        new GraphEdge("e2", "body", "exit-loop", null, null)), Map.of());

        assertThat(validator.validate(graph).issues()).isEmpty();
    }

    @Test
    void rejectsScopeStructureViolations() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "text", "label", "Text", "type", "text"))));
        GraphNode iteration = new GraphNode("iteration", "iteration", "Iteration", new Position(100, 0),
                Map.of("inputSelector", "user-input.text"), null);
        GraphNode first = new GraphNode("first", "template", "First", new Position(120, 0),
                Map.of("template", "a"), "iteration");
        GraphNode second = new GraphNode("second", "template", "Second", new Position(120, 100),
                Map.of("template", "b"), "iteration");
        GraphNode output = new GraphNode("output", "output", "Output", new Position(300, 0),
                Map.of("mappings", List.of(Map.of("name", "result", "selector", "first.output"))), null);
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, iteration, first, second, output),
                List.of(new GraphEdge("e1", "user-input", "iteration", null, null),
                        new GraphEdge("e2", "first", "output", null, null),
                        new GraphEdge("e3", "iteration", "output", null, null)), Map.of());

        List<String> codes = validator.validate(graph).issues().stream().map(ValidationIssue::code).toList();

        assertThat(codes).contains("SCOPE_ENTRY", "CROSS_SCOPE_EDGE");
    }

    @Test
    void rejectsExitLoopOutsideLoopAndNestedScope() {
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "text", "label", "Text", "type", "text"))));
        GraphNode iteration = new GraphNode("iteration", "iteration", "Iteration", new Position(100, 0),
                Map.of("inputSelector", "user-input.text"), null);
        GraphNode nested = new GraphNode("nested-loop", "loop", "Loop", new Position(120, 0),
                Map.of("maxIterations", 2), "iteration");
        GraphNode exit = new GraphNode("exit", "exit-loop", "Exit", new Position(200, 0),
                Map.of("conditions", List.of()), null);
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, iteration, nested, exit),
                List.of(new GraphEdge("e1", "user-input", "iteration", null, null),
                        new GraphEdge("e2", "iteration", "exit", null, null)), Map.of());

        List<String> codes = validator.validate(graph).issues().stream().map(ValidationIssue::code).toList();

        assertThat(codes).contains("NESTED_SCOPE", "SCOPE_FORBIDDEN", "EXIT_LOOP_SCOPE");
    }

    @Test
    void rejectsMissingOrNonFinitePositions() {
        GraphDocument example = GraphExamples.create(AppType.WORKFLOW);
        GraphNode original = example.nodes().getFirst();
        GraphNode invalid = new GraphNode(original.id(), original.type(), original.title(), null, original.config());
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW,
                List.of(invalid, example.nodes().get(1)), example.edges(), example.viewport());

        assertThat(validator.validate(graph).issues())
                .anyMatch(issue -> issue.code().equals("POSITION") && issue.path().equals("$.nodes[0].position"));
    }
}
