package com.acme.workflow.infra.execution;

import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class VariableAssignerExecutor implements NodeExecutor {
    @Override
    public String type() { return "variable-assigner"; }

    @Override
    public Map<String, Object> execute(NodeExecutionContext context) {
        Object rawName = context.node().config().get("variable");
        if (!(rawName instanceof String name) || name.isBlank())
            throw new NodeExecutionException(context.node().id(), "变量名必填。");
        if (!name.matches("[\\w-]+"))
            throw new NodeExecutionException(context.node().id(), "变量名只能包含字母、数字、下划线和短横线：" + name);
        Object value = resolve(context.node().config().get("value"), context);
        context.variables().put(name, value);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", value);
        result.put(name, value);
        return result;
    }

    private static Object resolve(Object raw, NodeExecutionContext context) {
        if (!(raw instanceof String text)) return raw;
        String trimmed = text.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) return context.select(trimmed);
        return text;
    }
}
