package com.acme.workflow.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluatorTest {
    private final Map<String, Map<String, Object>> outputs = Map.of("start", Map.of(
            "text", "Hello", "empty", "", "count", 10, "list", List.of("a", "b")));

    static Stream<Arguments> operators() {
        return Stream.of(
                Arguments.of("text", "equals", "Hello", true),
                Arguments.of("text", "not-equals", "Other", true),
                Arguments.of("text", "contains", "ell", true),
                Arguments.of("text", "not-contains", "xyz", true),
                Arguments.of("text", "starts-with", "He", true),
                Arguments.of("text", "ends-with", "lo", true),
                Arguments.of("empty", "is-empty", null, true),
                Arguments.of("text", "not-empty", null, true),
                Arguments.of("count", "greater", 9, true),
                Arguments.of("count", "greater-or-equal", 10, true),
                Arguments.of("count", "less", 11, true),
                Arguments.of("count", "less-or-equal", 10, true),
                Arguments.of("list", "contains", "a", true),
                Arguments.of("text", "equals", "other", false));
    }

    @ParameterizedTest
    @MethodSource("operators")
    void evaluatesConditionMatrix(String variable, String operator, Object value, boolean expected) {
        Map<String, Object> condition = value == null
                ? Map.of("variable", "start." + variable, "operator", operator)
                : Map.of("variable", "start." + variable, "operator", operator, "value", value);
        assertThat(ConditionEvaluator.evaluate(condition, outputs)).isEqualTo(expected);
    }

    @Test
    void supportsAndOrCases() {
        List<Map<String, Object>> conditions = List.of(
                Map.of("variable", "start.count", "operator", "greater", "value", 5),
                Map.of("variable", "start.text", "operator", "equals", "value", "other"));
        assertThat(ConditionEvaluator.evaluateCase(Map.of("logicalOperator", "and", "conditions", conditions), outputs)).isFalse();
        assertThat(ConditionEvaluator.evaluateCase(Map.of("logicalOperator", "or", "conditions", conditions), outputs)).isTrue();
    }
}
