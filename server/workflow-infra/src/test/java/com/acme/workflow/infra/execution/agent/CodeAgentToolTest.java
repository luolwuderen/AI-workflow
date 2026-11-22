package com.acme.workflow.infra.execution.agent;

import com.acme.workflow.infra.execution.SandboxClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAgentToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private CodeAgentTool tool;
    private volatile String requestBody;
    private volatile String responseBody = "{\"code\":0,\"message\":\"\",\"data\":{\"error\":\"\",\"stdout\":\"\"}}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sandbox/run", exchange -> {
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(payload);
            }
        });
        server.start();
        SandboxClient client = new SandboxClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", Duration.ofSeconds(5));
        tool = new CodeAgentTool(client);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void executesPythonAndReturnsMainResult() throws Exception {
        responseBody = sandboxResponse("__WORKFLOW_RESULT__{\"answer\": 42}");
        String result = tool.call(mapper.writeValueAsString(Map.of(
                "language", "python3", "code", "def main(**inputs):\n    return {\"answer\": 42}")));

        assertThat(mapper.readValue(result, Map.class)).isEqualTo(Map.of("answer", 42));
        assertThat(requestBody).contains("\"language\":\"python3\"").contains("\"enable_network\":false");
        assertThat(requestBody).contains("def main(**inputs)");
    }

    @Test
    void mapsJavascriptToNodejsSandboxLanguage() throws Exception {
        responseBody = sandboxResponse("__WORKFLOW_RESULT__{\"ok\": true}");
        tool.call(mapper.writeValueAsString(Map.of(
                "language", "javascript", "code", "function main(inputs) { return {ok: true}; }")));

        assertThat(requestBody).contains("\"language\":\"nodejs\"");
    }

    @Test
    void returnsSandboxErrorAsJsonInsteadOfThrowing() throws Exception {
        responseBody = "{\"code\":0,\"message\":\"\",\"data\":{\"error\":\"NameError: name 'x' is not defined\",\"stdout\":\"\"}}";
        String result = tool.call(mapper.writeValueAsString(Map.of(
                "language", "python3", "code", "def main(**inputs):\n    return x")));

        assertThat(String.valueOf(mapper.readValue(result, Map.class).get("error"))).contains("NameError");
    }

    @Test
    void rejectsUnsupportedLanguageWithoutCallingSandbox() throws Exception {
        String result = tool.call(mapper.writeValueAsString(Map.of("language", "ruby", "code", "def main; end")));

        assertThat(String.valueOf(mapper.readValue(result, Map.class).get("error"))).contains("python3");
        assertThat(requestBody).isNull();
    }

    @Test
    void rejectsMissingCode() throws Exception {
        String result = tool.call(mapper.writeValueAsString(Map.of("language", "python3")));

        assertThat(String.valueOf(mapper.readValue(result, Map.class).get("error"))).contains("code");
        assertThat(requestBody).isNull();
    }

    private static String sandboxResponse(String stdout) throws Exception {
        Map<String, Object> data = Map.of("error", "", "stdout", stdout);
        return new ObjectMapper().writeValueAsString(Map.of("code", 0, "message", "", "data", data));
    }
}
