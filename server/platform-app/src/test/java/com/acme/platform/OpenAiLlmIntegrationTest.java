package com.acme.platform;

import com.acme.workflow.infra.persistence.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow-openai-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.model.chat=openai"
})
@AutoConfigureMockMvc
class OpenAiLlmIntegrationTest {
    private static final AtomicReference<String> REQUEST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_PATH = new AtomicReference<>();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final HttpServer OPEN_AI = startServer();

    @Autowired private MockMvc mvc;
    @Autowired private JsonCodec json;

    @DynamicPropertySource
    static void openAiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + OPEN_AI.getAddress().getPort() + "/v1");
        registry.add("spring.ai.openai.api-key", () -> "contract-key");
    }

    @BeforeEach
    void resetRequest() {
        REQUEST_BODY.set(null);
        REQUEST_PATH.set(null);
        AUTHORIZATION.set(null);
    }

    @AfterAll
    static void stopServer() {
        OPEN_AI.stop(0);
    }

    @Test
    void callsOpenAiCompatibleEndpointWithRenderedMessagesAndStructuredOutput() throws Exception {
        Map<String, Object> llmConfig = Map.ofEntries(
                entry("model", "contract-model"),
                entry("messages", List.of(
                        Map.of("role", "system", "content", "Return JSON"),
                        Map.of("role", "user", "content", "Question: {{user-input.text}}"))),
                entry("temperature", 0.25), entry("topP", 0.8), entry("maxTokens", 128),
                entry("stop", List.of("END")), entry("responseFormat", "json_schema"),
                entry("jsonSchema", Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string")))),
                entry("stream", false));
        String runId = startLlmRun("OpenAI contract", llmConfig, "ping");
        Map<String, Object> run = awaitTerminalRun(runId);

        assertThat(run.get("status")).isEqualTo("SUCCEEDED");
        assertThat(run.get("outputs")).isEqualTo(Map.of("result", "{\"answer\":\"pong\"}"));
        assertThat(REQUEST_PATH.get()).isEqualTo("/v1/chat/completions");
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer contract-key");

        Map<String, Object> request = json.map(REQUEST_BODY.get());
        assertThat(request.get("model")).isEqualTo("contract-model");
        assertThat(request.get("temperature")).isEqualTo(0.25);
        assertThat(request.get("top_p")).isEqualTo(0.8);
        assertThat(request.get("max_tokens")).isEqualTo(128);
        assertThat(request.get("stop")).isEqualTo("END");
        String requestJson = json.write(request);
        assertThat(requestJson).contains("Question: ping").contains("Return JSON");
        assertThat(requestJson).contains("json_schema").contains("\"answer\"").contains("strict");
    }

    @Test
    void streamsOpenAiDeltasIntoRunEvents() throws Exception {
        Map<String, Object> llmConfig = Map.of(
                "model", "contract-model",
                "messages", List.of(Map.of("role", "user", "content", "Say {{user-input.text}}")),
                "responseFormat", "text", "stream", true);
        String runId = startLlmRun("OpenAI stream contract", llmConfig, "Hello");
        Map<String, Object> run = awaitTerminalRun(runId);

        assertThat(run.get("status")).isEqualTo("SUCCEEDED");
        assertThat(run.get("outputs")).isEqualTo(Map.of("result", "Hello"));
        assertThat(json.map(REQUEST_BODY.get()).get("stream")).isEqualTo(true);
        String events = mvc.perform(get("/api/v1/runs/{id}/events", runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(events).contains("LLM_DELTA").contains("\"delta\":\"Hel\"").contains("\"delta\":\"lo\"");
    }

    private String startLlmRun(String name, Map<String, Object> llmConfig, String input) throws Exception {
        String created = mvc.perform(post("/api/v1/apps").contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("name", name, "appType", "WORKFLOW"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String appId = String.valueOf(json.map(created).get("id"));
        Map<String, Object> graph = Map.of(
                "version", 1, "appType", "WORKFLOW",
                "nodes", List.of(
                        Map.of("id", "user-input", "type", "user-input", "title", "输入", "position", Map.of("x", 0, "y", 0),
                                "config", Map.of("fields", List.of(Map.of("variable", "text", "label", "文本", "type", "text", "required", true)))),
                        Map.of("id", "llm", "type", "llm", "title", "模型", "position", Map.of("x", 250, "y", 0), "config", llmConfig),
                        Map.of("id", "output", "type", "output", "title", "输出", "position", Map.of("x", 500, "y", 0),
                                "config", Map.of("mappings", List.of(Map.of("name", "result", "selector", "llm.text"))))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "user-input", "target", "llm"),
                        Map.of("id", "e2", "source", "llm", "target", "output")));
        mvc.perform(put("/api/v1/apps/{id}/draft", appId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("graph", graph, "expectedVersion", 0))))
                .andExpect(status().isOk());
        String started = mvc.perform(post("/api/v1/apps/{id}/runs", appId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("inputs", Map.of("text", input)))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return String.valueOf(json.map(started).get("id"));
    }

    private Map<String, Object> awaitTerminalRun(String runId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Map<String, Object> run = Map.of();
        while (System.nanoTime() < deadline) {
            String response = mvc.perform(get("/api/v1/runs/{id}", runId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            run = json.map(response);
            if ("SUCCEEDED".equals(run.get("status")) || "FAILED".equals(run.get("status"))) return run;
            Thread.sleep(25);
        }
        throw new AssertionError("运行未在 15 秒内结束：" + run);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                REQUEST_PATH.set(exchange.getRequestURI().getPath());
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                boolean stream = REQUEST_BODY.get().contains("\"stream\":true");
                byte[] response = (stream
                        ? "data: {\"id\":\"chatcmpl-contract\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"contract-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n" +
                        "data: {\"id\":\"chatcmpl-contract\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"contract-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                        : "{\"id\":\"chatcmpl-contract\",\"object\":\"chat.completion\",\"created\":1," +
                        "\"model\":\"contract-model\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"," +
                        "\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"answer\\\":\\\"pong\\\"}\"}}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, stream ? 0 : response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
