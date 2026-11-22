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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow-agent-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.model.chat=openai"
})
@AutoConfigureMockMvc
class AgentIntegrationTest {
    private static final List<String> REQUEST_BODIES = new ArrayList<>();
    private static final AtomicInteger CALL_COUNT = new AtomicInteger();
    private static final HttpServer OPEN_AI = startServer();

    @Autowired private MockMvc mvc;
    @Autowired private JsonCodec json;

    @DynamicPropertySource
    static void openAiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + OPEN_AI.getAddress().getPort() + "/v1");
        registry.add("spring.ai.openai.api-key", () -> "agent-key");
    }

    @BeforeEach
    void reset() {
        synchronized (REQUEST_BODIES) {
            REQUEST_BODIES.clear();
        }
        CALL_COUNT.set(0);
    }

    @AfterAll
    static void stopServer() {
        OPEN_AI.stop(0);
    }

    @Test
    void agentCallsToolThenProducesFinalAnswerWithToolCallEvent() throws Exception {
        Map<String, Object> agentConfig = Map.ofEntries(
                entry("model", "contract-model"),
                entry("instruction", "Compute {{user-input.text}}"),
                entry("strategy", "function_call"),
                entry("tools", List.of("calculator")),
                entry("maxIterations", 5),
                entry("temperature", 0.3));
        String runId = startAgentRun("Agent tool contract", agentConfig, "6*7");
        Map<String, Object> run = awaitTerminalRun(runId);

        assertThat(run.get("status")).isEqualTo("SUCCEEDED");
        assertThat(String.valueOf(run.get("outputs"))).contains("42");

        assertThat(CALL_COUNT.get()).isEqualTo(2);
        String firstRequest;
        String secondRequest;
        synchronized (REQUEST_BODIES) {
            firstRequest = REQUEST_BODIES.get(0);
            secondRequest = REQUEST_BODIES.get(1);
        }
        assertThat(firstRequest).contains("calculator").contains("\"tools\"");
        assertThat(secondRequest).contains("\"role\":\"tool\"").contains("42");

        String events = mvc.perform(get("/api/v1/runs/{id}/events", runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(events).contains("AGENT_TOOL_CALL").contains("calculator").contains("42");
    }

    private String startAgentRun(String name, Map<String, Object> agentConfig, String input) throws Exception {
        String created = mvc.perform(post("/api/v1/apps").contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("name", name, "appType", "WORKFLOW"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String appId = String.valueOf(json.map(created).get("id"));
        Map<String, Object> graph = Map.of(
                "version", 1, "appType", "WORKFLOW",
                "nodes", List.of(
                        Map.of("id", "user-input", "type", "user-input", "title", "输入", "position", Map.of("x", 0, "y", 0),
                                "config", Map.of("fields", List.of(Map.of("variable", "text", "label", "文本", "type", "text", "required", true)))),
                        Map.of("id", "agent", "type", "agent", "title", "智能体", "position", Map.of("x", 250, "y", 0), "config", agentConfig),
                        Map.of("id", "output", "type", "output", "title", "输出", "position", Map.of("x", 500, "y", 0),
                                "config", Map.of("mappings", List.of(Map.of("name", "result", "selector", "agent.text"))))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "user-input", "target", "agent"),
                        Map.of("id", "e2", "source", "agent", "target", "output")));
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
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                synchronized (REQUEST_BODIES) {
                    REQUEST_BODIES.add(body);
                }
                int call = CALL_COUNT.incrementAndGet();
                String response = call == 1
                        ? "{\"id\":\"chatcmpl-tool\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"contract-model\"," +
                        "\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\"," +
                        "\"message\":{\"role\":\"assistant\",\"content\":null," +
                        "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\"," +
                        "\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expression\\\":\\\"6*7\\\"}\"}}]}}]}"
                        : "{\"id\":\"chatcmpl-final\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"contract-model\"," +
                        "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"," +
                        "\"message\":{\"role\":\"assistant\",\"content\":\"The result is 42\"}}]}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
