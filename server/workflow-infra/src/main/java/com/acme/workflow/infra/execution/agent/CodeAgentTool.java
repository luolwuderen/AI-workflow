package com.acme.workflow.infra.execution.agent;

import com.acme.workflow.infra.execution.SandboxClient;
import com.acme.workflow.runtime.agent.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置代码工具：在 dify-sandbox 隔离环境中执行 python3 / javascript，禁用网络。
 * 输入 {"language":"python3","code":"def main(**inputs): ...","inputs":{...}}，
 * 输出 main 返回的对象；执行失败返回 {"error": "..."}，便于模型自我修正。
 */
@Component
public class CodeAgentTool implements AgentTool {

    public static final String NAME = "code";

    private final SandboxClient sandbox;
    private final ObjectMapper mapper = new ObjectMapper();

    public CodeAgentTool(SandboxClient sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在离线沙箱中执行代码。python3 需定义 def main(**inputs)，javascript 需定义 function main(inputs)；"
                + "返回值必须是 JSON 可序列化对象，例如 {\"answer\": 42}。沙箱禁止网络访问。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> language = new LinkedHashMap<>();
        language.put("type", "string");
        language.put("enum", List.of("python3", "javascript"));
        language.put("description", "代码语言");
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("type", "string");
        code.put("description", "包含 main 入口的完整代码");
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("type", "object");
        inputs.put("description", "传给 main 的入参，可省略");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("language", language);
        properties.put("code", code);
        properties.put("inputs", inputs);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("language", "code"));
        return schema;
    }

    @Override
    public String call(String argumentsJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = argumentsJson == null || argumentsJson.isBlank()
                    ? Map.of()
                    : mapper.readValue(argumentsJson, Map.class);
            String language = arguments.get("language") instanceof String value ? value : "";
            String code = arguments.get("code") instanceof String value ? value : "";
            if (language.isBlank()) return error("缺少必填参数 language。");
            if (!List.of("python3", "javascript").contains(language)) return error("仅支持 python3 或 javascript。");
            if (code.isBlank()) return error("缺少必填参数 code。");
            Map<String, Object> inputs = new LinkedHashMap<>();
            if (arguments.get("inputs") instanceof Map<?, ?> provided) {
                provided.forEach((key, value) -> inputs.put(String.valueOf(key), value));
            }
            return mapper.writeValueAsString(sandbox.run(language, inputs, code));
        } catch (IllegalArgumentException error) {
            return error(error.getMessage());
        } catch (Exception error) {
            return error("代码执行失败：" + error.getMessage());
        }
    }

    private String error(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", message == null ? "代码执行失败。" : message));
        } catch (Exception ignored) {
            return "{\"error\":\"代码执行失败。\"}";
        }
    }
}
