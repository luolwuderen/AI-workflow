package com.acme.workflow.runtime;

import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphNode;

import java.util.Map;
import java.util.function.BiConsumer;

public record NodeExecutionContext(String runId, GraphDocument graph, GraphNode node, Map<String, Object> runInputs,
                                   Map<String, Map<String, Object>> nodeOutputs, Map<String, Object> variables,
                                   BiConsumer<String, Map<String, Object>> events) {
    public Object select(String selector) {
        return Variables.select(selector, nodeOutputs, variables);
    }

    public String render(String template) {
        return Variables.render(template, nodeOutputs, variables);
    }

    public void emit(String type, Map<String, Object> data) {
        events.accept(type, data);
    }
}
