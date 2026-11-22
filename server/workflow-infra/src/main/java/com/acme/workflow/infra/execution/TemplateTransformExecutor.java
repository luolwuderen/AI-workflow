package com.acme.workflow.infra.execution;

import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import com.acme.workflow.runtime.Variables;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TemplateTransformExecutor implements NodeExecutor {
    @Override
    public String type() { return "template"; }

    @Override
    public Map<String, Object> execute(NodeExecutionContext context) {
        Object rawTemplate = context.node().config().get("template");
        if (!(rawTemplate instanceof String template) || template.isBlank())
            throw new NodeExecutionException(context.node().id(), "template 必填。");
        Map<String, Object> locals = new LinkedHashMap<>(context.variables());
        Object configured = context.node().config().get("variables");
        if (configured != null) {
            if (!(configured instanceof List<?> variables))
                throw new NodeExecutionException(context.node().id(), "variables 必须为数组。");
            for (Object item : variables) {
                if (!(item instanceof Map<?, ?> variable))
                    throw new NodeExecutionException(context.node().id(), "variables 每项必须为对象。");
                Object name = variable.get("variable");
                if (!(name instanceof String variableName) || variableName.isBlank())
                    throw new NodeExecutionException(context.node().id(), "variables 每项必须声明变量名。");
                Object selector = variable.get("selector");
                if (!(selector instanceof String selectorText) || selectorText.isBlank())
                    throw new NodeExecutionException(context.node().id(), "变量 " + variableName + " 缺少 selector。");
                locals.put(variableName, context.select(selectorText));
            }
        }
        return Map.of("output", Variables.render(template, context.nodeOutputs(), locals));
    }
}
