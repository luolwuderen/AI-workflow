package com.acme.workflow.infra.execution.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalculatorAgentToolTest {

    private final CalculatorAgentTool tool = new CalculatorAgentTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluatesBasicArithmetic() throws Exception {
        Map<String, Object> result = call("2 + 3 * 4");
        assertThat(result).containsEntry("result", 14.0);
    }

    @Test
    void respectsParenthesesAndUnaryMinus() throws Exception {
        assertThat(call("2 * (3 + 4)")).containsEntry("result", 14.0);
        assertThat(call("-5 + 3")).containsEntry("result", -2.0);
        assertThat(call("-(2 + 3)")).containsEntry("result", -5.0);
    }

    @Test
    void supportsDivisionAndModulo() throws Exception {
        assertThat(call("10 / 4")).containsEntry("result", 2.5);
        assertThat(call("10 % 3")).containsEntry("result", 1.0);
    }

    @Test
    void rejectsDivisionByZeroWithErrorJson() throws Exception {
        Map<String, Object> result = call("1 / 0");
        assertThat(result).containsKey("error");
        assertThat(String.valueOf(result.get("error"))).contains("除数不能为零");
    }

    @Test
    void rejectsMalformedExpressionWithErrorJson() throws Exception {
        assertThat(call("2 +")).containsKey("error");
        assertThat(call("abc")).containsKey("error");
        assertThat(call("(1 + 2")).containsKey("error");
    }

    @Test
    void missingExpressionParamReturnsErrorJson() throws Exception {
        Map<String, Object> result = parse(tool.call("{\"foo\":1}"));
        assertThat(result).containsKey("error");
        assertThat(tool.name()).isEqualTo("calculator");
        assertThat(tool.inputSchema()).containsKey("properties");
    }

    @Test
    void parserThrowsForBlankInput() {
        assertThatThrownBy(() -> ArithmeticExpression.evaluate("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, Object> call(String expression) throws Exception {
        return parse(tool.call(mapper.writeValueAsString(Map.of("expression", expression))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) throws Exception {
        return mapper.readValue(json, Map.class);
    }
}
