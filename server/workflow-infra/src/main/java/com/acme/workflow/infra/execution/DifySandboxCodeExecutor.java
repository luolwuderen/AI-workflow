package com.acme.workflow.infra.execution;

import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DifySandboxCodeExecutor implements NodeExecutor {
    private static final String MARKER = "__WORKFLOW_RESULT__";
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public DifySandboxCodeExecutor(RestClient.Builder builder, @Value("${workflow.sandbox.base-url:http://localhost:8194}") String baseUrl,
                                   @Value("${workflow.sandbox.api-key:dify-sandbox}") String apiKey,
                                   @Value("${workflow.sandbox.timeout:10s}") Duration timeout) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(timeout);
        requests.setReadTimeout(timeout);
        this.client = builder.baseUrl(baseUrl).defaultHeader("X-Api-Key", apiKey).requestFactory(requests).build();
    }

    @Override
    public String type() { return "code"; }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(NodeExecutionContext context) {
        try {
            Map<String, Object> config = context.node().config();
            String language = String.valueOf(config.get("language"));
            Map<String, Object> inputs = inputs(config.get("variables") != null ? config.get("variables") : config.get("inputs"), context);
            String code = wrap(language, String.valueOf(config.get("code")), inputs);
            String sandboxLanguage = "javascript".equals(language) ? "nodejs" : language;
            Map<String, Object> response = client.post().uri("/v1/sandbox/run")
                    .body(Map.of("language", sandboxLanguage, "code", code, "preload", "", "enable_network", false))
                    .retrieve().body(Map.class);
            if (response == null) throw new IllegalArgumentException("Sandbox 返回空响应。");
            if (response.get("code") instanceof Number codeValue && codeValue.intValue() != 0)
                throw new IllegalArgumentException("Sandbox 错误：" + response.get("message"));
            Object rawData = response.get("data");
            if (!(rawData instanceof Map<?, ?> data)) throw new IllegalArgumentException("Sandbox 响应缺少 data。");
            String error = data.get("error") == null ? "" : String.valueOf(data.get("error"));
            if (!error.isBlank()) throw new IllegalArgumentException("代码执行错误：" + error);
            String stdout = data.get("stdout") == null ? "" : String.valueOf(data.get("stdout"));
            if (stdout.length() > 1_000_000) throw new IllegalArgumentException("代码输出超过 1 MB 限制。");
            int marker = stdout.lastIndexOf(MARKER);
            if (marker < 0) throw new IllegalArgumentException("代码未返回 main 声明的结果：" + stdout);
            String json = stdout.substring(marker + MARKER.length()).trim();
            Object result = mapper.readValue(json, Object.class);
            if (!(result instanceof Map<?, ?> resultMap)) throw new IllegalArgumentException("main 必须返回输出对象。");
            return validateOutputs(config.get("outputs"), resultMap);
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

    private String wrap(String language, String code, Map<String, Object> inputs) throws Exception {
        String inputsJson = mapper.writeValueAsString(inputs);
        String literal = mapper.writeValueAsString(inputsJson);
        return switch (language) {
            case "python3" -> code + "\n\nimport json as __workflow_json\n__workflow_inputs = __workflow_json.loads(" + literal +
                    ")\n__workflow_result = main(**__workflow_inputs)\nprint('" + MARKER + "' + __workflow_json.dumps(__workflow_result))\n";
            case "javascript" -> code + "\n\nconst __workflowInputs = JSON.parse(" + literal + ");\n" +
                    "const __workflowResult = main(__workflowInputs);\nconsole.log('" + MARKER + "' + JSON.stringify(__workflowResult));\n";
            default -> throw new IllegalArgumentException("仅支持 python3 或 javascript。");
        };
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
