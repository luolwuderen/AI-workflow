package com.acme.workflow.infra.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过官方 dify-sandbox HTTP API 执行代码，供 code 节点与 Agent code 工具复用。
 * 被执行的代码需声明 main 入口并返回 JSON 可序列化对象；执行时禁用网络。
 */
@Component
public class SandboxClient {
    private static final String MARKER = "__WORKFLOW_RESULT__";

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public SandboxClient(RestClient.Builder builder, @Value("${workflow.sandbox.base-url:http://localhost:8194}") String baseUrl,
                         @Value("${workflow.sandbox.api-key:dify-sandbox}") String apiKey,
                         @Value("${workflow.sandbox.timeout:10s}") Duration timeout) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(timeout);
        requests.setReadTimeout(timeout);
        this.client = builder.baseUrl(baseUrl).defaultHeader("X-Api-Key", apiKey).requestFactory(requests).build();
    }

    /**
     * 执行代码并返回 main 返回的对象。language 支持 python3 与 javascript，
     * inputs 会作为 main 的入参注入。
     */
    public Map<String, Object> run(String language, Map<String, Object> inputs, String code) {
        try {
            String sandboxLanguage = "javascript".equals(language) ? "nodejs" : language;
            String wrapped = wrap(language, code, inputs == null ? Map.of() : inputs);
            Map<String, Object> response = client.post().uri("/v1/sandbox/run")
                    .body(Map.of("language", sandboxLanguage, "code", wrapped, "preload", "", "enable_network", false))
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
            Object result = mapper.readValue(stdout.substring(marker + MARKER.length()).trim(), Object.class);
            if (!(result instanceof Map<?, ?> resultMap)) throw new IllegalArgumentException("main 必须返回输出对象。");
            Map<String, Object> outputs = new LinkedHashMap<>();
            resultMap.forEach((key, value) -> outputs.put(String.valueOf(key), value));
            return outputs;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Sandbox 执行失败：" + error.getMessage(), error);
        }
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
}
