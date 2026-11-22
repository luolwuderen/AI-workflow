package com.acme.workflow.infra.execution;

import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VariableAggregatorExecutor implements NodeExecutor {
    @Override
    public String type() { return "variable-aggregator"; }

    @Override
    public Map<String, Object> execute(NodeExecutionContext context) {
        Object configured = context.node().config().get("variables");
        if (!(configured instanceof List<?> variables) || variables.isEmpty())
            throw new NodeExecutionException(context.node().id(), "至少配置一个聚合变量。");
        for (Object item : variables) {
            String selector = selector(item);
            if (selector == null || selector.isBlank())
                throw new NodeExecutionException(context.node().id(), "聚合变量的 selector 必填。");
            Object value = context.select(selector);
            if (!empty(value)) return Map.of("output", value);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", "");
        return result;
    }

    private static String selector(Object item) {
        if (item instanceof String text) return text;
        if (item instanceof Map<?, ?> variable && variable.get("selector") != null)
            return String.valueOf(variable.get("selector"));
        return null;
    }

    private static boolean empty(Object value) {
        return value == null || (value instanceof String text && text.isBlank()) ||
                (value instanceof Collection<?> collection && collection.isEmpty()) ||
                (value instanceof Map<?, ?> map && map.isEmpty());
    }
}
