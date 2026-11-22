package com.acme.workflow.runtime;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Variables {
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([\\w-]+)\\.([\\w.-]+)\\s*}}");
    private static final Pattern RUN_VARIABLE = Pattern.compile("\\{\\{\\s*([\\w-]+)\\s*}}");

    private Variables() { }

    public static Object select(String selector, Map<String, Map<String, Object>> outputs) {
        return select(selector, outputs, Map.of());
    }

    public static Object select(String selector, Map<String, Map<String, Object>> outputs, Map<String, Object> variables) {
        if (selector == null) return null;
        String normalized = selector.trim();
        if (normalized.startsWith("{{") && normalized.endsWith("}}"))
            normalized = normalized.substring(2, normalized.length() - 2).trim();
        int separator = normalized.indexOf('.');
        if (separator < 0 && variables != null && variables.containsKey(normalized)) return variables.get(normalized);
        if (separator < 1) throw new IllegalArgumentException("变量选择器必须为 nodeId.key：" + selector);
        Object current = outputs.get(normalized.substring(0, separator));
        if (current == null) throw new IllegalArgumentException("变量节点尚未执行或不存在：" + selector);
        for (String part : normalized.substring(separator + 1).split("\\.")) {
            if (current instanceof Map<?, ?> map) current = map.get(part);
            else if (current instanceof List<?> list && part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                current = index < list.size() ? list.get(index) : null;
            } else throw new IllegalArgumentException("无法读取变量路径：" + selector);
        }
        return current;
    }

    public static String render(String template, Map<String, Map<String, Object>> outputs) {
        return render(template, outputs, Map.of());
    }

    public static String render(String template, Map<String, Map<String, Object>> outputs, Map<String, Object> variables) {
        if (template == null) return "";
        String expanded = expandRunVariables(template, variables);
        Matcher matcher = TEMPLATE.matcher(expanded);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = select(matcher.group(1) + "." + matcher.group(2), outputs);
            matcher.appendReplacement(result, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String expandRunVariables(String template, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) return template;
        Matcher matcher = RUN_VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!variables.containsKey(name)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(stringify(variables.get(name))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static BigDecimal decimal(Object value) {
        if (value == null) throw new IllegalArgumentException("数值比较的操作数不能为空。");
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("数值比较的操作数不是数字：" + value, error); }
    }
}
