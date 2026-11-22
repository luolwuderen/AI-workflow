package com.acme.workflow.infra.execution;

import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DifySandboxCodeExecutor implements NodeExecutor {
    private final SandboxClient sandbox;

    public DifySandboxCodeExecutor(SandboxClient sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String type() { return "code"; }

    @Override
    public Map<String, Object> execute(NodeExecutionContext context) {
        try {
            Map<String, Object> config = context.node().config();
            String language = String.valueOf(config.get("language"));
            Map<String, Object> inputs = inputs(config.get("variables") != null ? config.get("variables") : config.get("inputs"), context);
            Map<String, Object> result = sandbox.run(language, inputs, String.valueOf(config.get("code")));
            return validateOutputs(config.get("outputs"), result);
        } catch (Exception error) {
            throw new NodeExecutionException(context.node().id(), "Sandbox 执行失败：" + error.getMessage(), error);
        }
    }

    private Map<String, Object> inputs(Object raw, NodeExecutionContext context) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (raw == null) return inputs;
        if (!(raw instanceof List<?> configured)) throw new IllegalArgumentException("代码 inputs 必须为数组。");
        for (Object item : configured) {
            if (!(item instanceof Map<?, ?> input)) throw new IllegalArgumentException("代码输入必须为对象。");
            String name = String.valueOf(input.containsKey("name") ? input.get("name") : input.get("variable"));
            inputs.put(name, context.select(String.valueOf(input.get("selector"))));
        }
        return inputs;
    }

    private Map<String, Object> validateOutputs(Object raw, Map<?, ?> result) {
        if (!(raw instanceof Map<?, ?> declarations)) throw new IllegalArgumentException("outputs 声明必须为对象。");
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : declarations.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!result.containsKey(name)) throw new IllegalArgumentException("代码缺少声明输出 " + name + "。");
            Object value = result.get(name);
            String type = entry.getValue() instanceof Map<?, ?> declaration ? String.valueOf(declaration.get("type")) : String.valueOf(entry.getValue());
            boolean valid = switch (type) {
                case "string" -> value instanceof String;
                case "number" -> value instanceof Number;
                case "boolean" -> value instanceof Boolean;
                case "object" -> value instanceof Map<?, ?>;
                case "array" -> value instanceof List<?>;
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("输出 " + name + " 不符合声明类型 " + type + "。");
            outputs.put(name, value);
        }
        return outputs;
    }
}
