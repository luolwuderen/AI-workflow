package com.acme.workflow.runtime;

import com.acme.workflow.core.model.GraphNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class FormInputValidator {
    private FormInputValidator() { }

    public static Map<String, Object> validate(GraphNode node, Map<String, Object> inputs) {
        Map<String, Object> safeInputs = inputs == null ? Map.of() : inputs;
        Object rawFields = node.config().get("fields");
        if (!(rawFields instanceof List<?> fields)) throw new NodeExecutionException(node.id(), "fields 配置无效。");
        Set<String> known = fields.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(field -> String.valueOf(field.get("variable"))).collect(Collectors.toSet());
        List<String> unknown = safeInputs.keySet().stream().filter(key -> !known.contains(key)).sorted().toList();
        if (!unknown.isEmpty()) throw new NodeExecutionException(node.id(), "存在未声明的运行输入：" + String.join(", ", unknown));
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object raw : fields) {
            if (!(raw instanceof Map<?, ?> field)) continue;
            String name = String.valueOf(field.get("variable"));
            Object value = safeInputs.containsKey(name) ? safeInputs.get(name) : field.get("default");
            boolean required = Boolean.TRUE.equals(field.get("required"));
            if (value == null || (required && value instanceof String text && text.isBlank())) {
                if (required) throw new NodeExecutionException(node.id(), "缺少必填输入 " + name + "。");
                result.put(name, null);
                continue;
            }
            String type = String.valueOf(field.get("type"));
            boolean valid = switch (type) {
                case "text", "paragraph" -> value instanceof String;
                case "number" -> value instanceof Number;
                case "boolean" -> value instanceof Boolean;
                case "json" -> value instanceof Map<?, ?> || value instanceof List<?>;
                case "select" -> value instanceof String && (!(field.get("options") instanceof List<?> options) || options.contains(value));
                default -> false;
            };
            if (!valid) throw new NodeExecutionException(node.id(), "输入 " + name + " 不符合 " + type + " 类型或选项约束。");
            result.put(name, value);
        }
        return result;
    }
}
