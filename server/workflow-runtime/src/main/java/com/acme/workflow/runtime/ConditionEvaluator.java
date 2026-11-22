package com.acme.workflow.runtime;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ConditionEvaluator {
    private ConditionEvaluator() { }

    public static boolean evaluateCase(Map<?, ?> oneCase, Map<String, Map<String, Object>> outputs) {
        return evaluateCase(oneCase, outputs, Map.of());
    }

    public static boolean evaluateCase(Map<?, ?> oneCase, Map<String, Map<String, Object>> outputs,
                                       Map<String, Object> variables) {
        Object rawConditions = oneCase.get("conditions");
        if (!(rawConditions instanceof List<?> conditions) || conditions.isEmpty())
            throw new IllegalArgumentException("IF case 至少需要一个条件。");
        return evaluateAll(conditions, String.valueOf(oneCase.get("logicalOperator")), outputs, variables);
    }

    public static boolean evaluateAll(List<?> conditions, String logicalOperator,
                                      Map<String, Map<String, Object>> outputs, Map<String, Object> variables) {
        boolean and = !"or".equals(logicalOperator);
        boolean result = and;
        for (Object raw : conditions) {
            if (!(raw instanceof Map<?, ?> condition)) throw new IllegalArgumentException("条件必须为对象。");
            boolean current = evaluate(condition, outputs, variables);
            result = and ? result && current : result || current;
        }
        return result;
    }

    public static boolean evaluate(Map<?, ?> condition, Map<String, Map<String, Object>> outputs) {
        return evaluate(condition, outputs, Map.of());
    }

    public static boolean evaluate(Map<?, ?> condition, Map<String, Map<String, Object>> outputs,
                                   Map<String, Object> variables) {
        Object actual = Variables.select(String.valueOf(condition.get("variable")), outputs, variables);
        Object expected = condition.get("value");
        String operator = String.valueOf(condition.get("operator"));
        String left = Variables.stringify(actual);
        String right = Variables.stringify(expected);
        return switch (operator) {
            case "equals" -> Objects.equals(actual, expected) || left.equals(right);
            case "not-equals" -> !(Objects.equals(actual, expected) || left.equals(right));
            case "contains" -> contains(actual, right);
            case "not-contains" -> !contains(actual, right);
            case "starts-with" -> left.startsWith(right);
            case "ends-with" -> left.endsWith(right);
            case "is-empty" -> empty(actual);
            case "not-empty" -> !empty(actual);
            case "greater" -> Variables.decimal(actual).compareTo(Variables.decimal(expected)) > 0;
            case "greater-or-equal" -> Variables.decimal(actual).compareTo(Variables.decimal(expected)) >= 0;
            case "less" -> Variables.decimal(actual).compareTo(Variables.decimal(expected)) < 0;
            case "less-or-equal" -> Variables.decimal(actual).compareTo(Variables.decimal(expected)) <= 0;
            default -> throw new IllegalArgumentException("不支持的条件操作符：" + operator);
        };
    }

    private static boolean contains(Object actual, String expected) {
        if (actual instanceof Collection<?> collection) return collection.stream().anyMatch(item -> Variables.stringify(item).equals(expected));
        return Variables.stringify(actual).contains(expected);
    }

    private static boolean empty(Object value) {
        return value == null || (value instanceof String text && text.isEmpty()) ||
                (value instanceof Collection<?> collection && collection.isEmpty()) ||
                (value instanceof Map<?, ?> map && map.isEmpty());
    }
}
