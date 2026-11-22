package com.acme.workflow.core.model;

import java.util.Map;

public record GraphNode(String id, String type, String title, Position position, Map<String, Object> config,
                        String parentId) {
    public GraphNode {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public GraphNode(String id, String type, String title, Position position, Map<String, Object> config) {
        this(id, type, title, position, config, null);
    }
}
