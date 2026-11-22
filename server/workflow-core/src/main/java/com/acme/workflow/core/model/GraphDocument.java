package com.acme.workflow.core.model;

import java.util.List;
import java.util.Map;

public record GraphDocument(int version, AppType appType, List<GraphNode> nodes, List<GraphEdge> edges,
                            Map<String, Object> viewport) {
    public GraphDocument {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
