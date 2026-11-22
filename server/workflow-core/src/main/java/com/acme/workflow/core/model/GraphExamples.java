package com.acme.workflow.core.model;

import java.util.List;
import java.util.Map;

public final class GraphExamples {
    private GraphExamples() { }

    public static GraphDocument create(AppType appType) {
        GraphNode input = new GraphNode("user-input", "user-input", "User Input", new Position(80, 160),
                Map.of("fields", List.of(Map.of("variable", "text", "label", "文本", "type", "text", "required", true, "default", "Hello"))));
        if (appType == AppType.CHATFLOW) {
            GraphNode answer = new GraphNode("answer", "answer", "Answer", new Position(420, 160),
                    Map.of("answer", "你好，{{user-input.text}}"));
            return new GraphDocument(1, appType, List.of(input, answer),
                    List.of(new GraphEdge("edge-1", input.id(), answer.id(), null, null)), Map.of("x", 0, "y", 0, "zoom", 1));
        }
        GraphNode output = new GraphNode("output", "output", "Output", new Position(420, 160),
                Map.of("mappings", List.of(Map.of("name", "message", "selector", "user-input.text"))));
        return new GraphDocument(1, appType, List.of(input, output),
                List.of(new GraphEdge("edge-1", input.id(), output.id(), null, null)), Map.of("x", 0, "y", 0, "zoom", 1));
    }
}
