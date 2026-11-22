package com.acme.workflow.infra.execution.agent;

import com.acme.workflow.runtime.agent.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首版内置 Agent 工具：安全算术计算器。
 * 输入 {"expression": "2 * (3 + 4)"}，输出 {"result": 14, "expression": "..."} 或 {"error": "..."}。
 * 计算失败时返回错误 JSON 而不是抛出，便于模型自我修正。
 */
@Component
public class CalculatorAgentTool implements AgentTool {

    public static final String NAME = "calculator";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "计算算术表达式，支持加减乘除、取模、括号与小数，例如 2 * (3 + 4) / 5。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> expression = new LinkedHashMap<>();
        expression.put("type", "string");
        expression.put("description", "要计算的算术表达式，例如 2 * (3 + 4)");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("expression", expression);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("expression"));
        return schema;
    }

    @Override
    public String call(String argumentsJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = argumentsJson == null || argumentsJson.isBlank()
                    ? Map.of()
                    : mapper.readValue(argumentsJson, Map.class);
            Object raw = arguments.get("expression");
            if (!(raw instanceof String expression) || expression.isBlank()) {
                return error("缺少必填参数 expression。");
            }
            double result = ArithmeticExpression.evaluate(expression);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("result", result);
            output.put("expression", expression);
            return mapper.writeValueAsString(output);
        } catch (IllegalArgumentException error) {
            return error(error.getMessage());
        } catch (Exception error) {
            return error("参数解析失败：" + error.getMessage());
        }
    }

    private String error(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", message == null ? "计算失败。" : message));
        } catch (Exception ignored) {
            return "{\"error\":\"计算失败。\"}";
        }
    }
}
