package com.acme.workflow.infra.execution;

import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import com.acme.workflow.runtime.Variables;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ListOperatorExecutor implements NodeExecutor {
    @Override
    public String type() { return "list-operator"; }

    @Override
    public Map<String, Object> execute(NodeExecutionContext context) {
        Map<String, Object> config = context.node().config();
        Object rawSelector = config.get("inputSelector");
        if (!(rawSelector instanceof String selector) || selector.isBlank())
            throw new NodeExecutionException(context.node().id(), "inputSelector 必填。");
        Object raw = context.select(selector);
        if (!(raw instanceof List<?> list))
            throw new NodeExecutionException(context.node().id(), "inputSelector 必须指向数组：" + selector);
        List<Object> values = new ArrayList<>(list);

        String operator = text(config.get("filterOperator"), "none");
        if (!"none".equals(operator)) {
            values = filter(context.node().id(), values, operator, text(config.get("filterKey"), ""), config.get("filterValue"));
        }
        if (Boolean.TRUE.equals(config.get("sortEnabled"))) {
            Comparator<Object> comparator = comparator(text(config.get("sortKey"), ""));
            if ("desc".equals(text(config.get("sortOrder"), "asc"))) comparator = comparator.reversed();
            values.sort(comparator);
        }

        Object result = values;
        int serial = number(config.get("extractSerial"));
        if (serial > 0) {
            if (serial > values.size())
                throw new NodeExecutionException(context.node().id(), "提取位置 " + serial + " 超出列表长度 " + values.size() + "。");
            result = values.get(serial - 1);
        } else {
            int limit = number(config.get("limit"));
            if (limit > 0 && values.size() > limit) result = new ArrayList<>(values.subList(0, limit));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("output", result);
        return output;
    }

    private List<Object> filter(String nodeId, List<Object> values, String operator, String key, Object expected) {
        List<Object> result = new ArrayList<>();
        for (Object item : values) {
            Object actual = key.isBlank() ? item
                    : item instanceof Map<?, ?> map ? map.get(key) : null;
            if (matches(nodeId, actual, operator, expected)) result.add(item);
        }
        return result;
    }

    private boolean matches(String nodeId, Object actual, String operator, Object expected) {
        String left = Variables.stringify(actual);
        String right = Variables.stringify(expected);
        return switch (operator) {
            case "equals" -> Objects.equals(actual, expected) || left.equals(right);
            case "not-equals" -> !(Objects.equals(actual, expected) || left.equals(right));
            case "contains" -> contains(actual, right);
            case "greater" -> compare(actual, expected) > 0;
            case "less" -> compare(actual, expected) < 0;
            default -> throw new NodeExecutionException(nodeId, "不支持的过滤操作：" + operator);
        };
    }

    private static boolean contains(Object actual, String expected) {
        if (actual instanceof Map<?, ?> map) return map.containsKey(expected);
        if (actual instanceof Collection<?> collection)
            return collection.stream().anyMatch(item -> Variables.stringify(item).equals(expected));
        return Variables.stringify(actual).contains(expected);
    }

    private static Comparator<Object> comparator(String key) {
        return (left, right) -> {
            Object leftValue = key.isBlank() ? left : left instanceof Map<?, ?> map ? map.get(key) : null;
            Object rightValue = key.isBlank() ? right : right instanceof Map<?, ?> map ? map.get(key) : null;
            return compare(leftValue, rightValue);
        };
    }

    private static int compare(Object left, Object right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        String leftText = String.valueOf(left);
        String rightText = String.valueOf(right);
        try {
            return new BigDecimal(leftText).compareTo(new BigDecimal(rightText));
        } catch (NumberFormatException ignored) {
            return leftText.compareTo(rightText);
        }
    }

    private static String text(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static int number(Object raw) {
        return raw instanceof Number value ? value.intValue() : 0;
    }
}
