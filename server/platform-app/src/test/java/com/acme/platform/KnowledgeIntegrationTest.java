package com.acme.platform;

import com.acme.workflow.infra.persistence.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow-knowledge-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.model.chat=openai",
        "spring.ai.openai.embedding.dimensions=3"
})
@AutoConfigureMockMvc
class KnowledgeIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpServer OPEN_AI = startServer();

    @Autowired private MockMvc mvc;
    @Autowired private JsonCodec json;

    @DynamicPropertySource
    static void openAiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + OPEN_AI.getAddress().getPort() + "/v1");
        registry.add("spring.ai.openai.api-key", () -> "knowledge-key");
    }

    @AfterAll
    static void stopServer() {
        OPEN_AI.stop(0);
    }

    @Test
    void ingestDocumentThenRetrieveMatchingChunkInWorkflowRun() throws Exception {
        String base = mvc.perform(post("/api/v1/knowledge-bases").contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("name", "水果知识", "description", "测试"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String knowledgeBaseId = String.valueOf(json.map(base).get("id"));

        String document = mvc.perform(post("/api/v1/knowledge-bases/{id}/documents", knowledgeBaseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("name", "水果简介", "content", "苹果是一种常见水果。"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(((Number) json.map(document).get("chunkCount")).intValue()).isEqualTo(1);

        String documents = mvc.perform(get("/api/v1/knowledge-bases/{id}/documents", knowledgeBaseId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(documents).contains("水果简介");

        String app = mvc.perform(post("/api/v1/apps").contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("name", "Knowledge run", "appType", "WORKFLOW"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String appId = String.valueOf(json.map(app).get("id"));
        Map<String, Object> graph = Map.of(
                "version", 1, "appType", "WORKFLOW",
                "nodes", List.of(
                        Map.of("id", "user-input", "type", "user-input", "title", "输入",
                                "position", Map.of("x", 0, "y", 0),
                                "config", Map.of("fields", List.of(Map.of("variable", "text", "label", "文本",
                                        "type", "text", "required", true)))),
                        Map.of("id", "retrieval", "type", "knowledge-retrieval", "title", "检索",
                                "position", Map.of("x", 250, "y", 0),
                                "config", Map.of("datasetIds", List.of(knowledgeBaseId),
                                        "query", "{{user-input.text}}", "topK", 2, "scoreThreshold", 0.5)),
                        Map.of("id", "output", "type", "output", "title", "输出",
                                "position", Map.of("x", 500, "y", 0),
                                "config", Map.of("mappings", List.of(
                                        Map.of("name", "context", "selector", "retrieval.context"),
                                        Map.of("name", "count", "selector", "retrieval.count"))))),
                "edges", List.of(
                        Map.of("id", "e1", "source", "user-input", "target", "retrieval"),
                        Map.of("id", "e2", "source", "retrieval", "target", "output")));
        mvc.perform(put("/api/v1/apps/{id}/draft", appId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("graph", graph, "expectedVersion", 0))))
                .andExpect(status().isOk());

        String started = mvc.perform(post("/api/v1/apps/{id}/runs", appId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("inputs", Map.of("text", "苹果")))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = String.valueOf(json.map(started).get("id"));

        Map<String, Object> run = awaitTerminalRun(runId);
        assertThat(run.get("status")).isEqualTo("SUCCEEDED");
        assertThat(String.valueOf(run.get("outputs"))).contains("苹果是一种常见水果").contains("1");
    }

    @Test
    void searchEndpointReturnsScoredHitsAndDocumentDetail() throws Exception {
        String base = mvc.perform(post("/api/v1/knowledge-bases").contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("name", "水果检索", "description", "调试"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String knowledgeBaseId = String.valueOf(json.map(base).get("id"));

        String document = mvc.perform(post("/api/v1/knowledge-bases/{id}/documents", knowledgeBaseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("name", "苹果文档", "content", "苹果富含维生素。"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String documentId = String.valueOf(json.map(document).get("id"));

        String detail = mvc.perform(get("/api/v1/knowledge-bases/{id}/documents/{documentId}",
                        knowledgeBaseId, documentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("苹果文档").contains("苹果富含维生素。");

        String hits = mvc.perform(post("/api/v1/knowledge-bases/search").contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("datasetIds", List.of(knowledgeBaseId),
                                "query", "苹果", "topK", 5, "scoreThreshold", 0.0))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode nodes = MAPPER.readTree(hits);
        assertThat(nodes.isArray()).isTrue();
        assertThat(nodes.size()).isEqualTo(1);
        assertThat(nodes.get(0).path("documentName").asText()).isEqualTo("苹果文档");
        assertThat(nodes.get(0).path("content").asText()).contains("苹果");
        assertThat(nodes.get(0).path("score").asDouble()).isGreaterThan(0.99);

        String filtered = mvc.perform(post("/api/v1/knowledge-bases/search").contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("datasetIds", List.of(knowledgeBaseId),
                                "query", "香蕉", "topK", 5, "scoreThreshold", 0.5))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(MAPPER.readTree(filtered).size()).isZero();
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
                JsonNode root = MAPPER.readTree(body);
                JsonNode input = root.path("input");
                List<String> texts = new ArrayList<>();
                if (input.isArray()) input.forEach(node -> texts.add(node.asText()));
                else if (input.isTextual()) texts.add(input.asText());
                StringBuilder data = new StringBuilder();
                for (int index = 0; index < texts.size(); index++) {
                    float[] vector = texts.get(index).contains("苹果") ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
                    if (index > 0) data.append(',');
                    data.append("{\"object\":\"embedding\",\"index\":").append(index)
                            .append(",\"embedding\":[").append(vector[0]).append(',').append(vector[1])
                            .append(',').append(vector[2]).append("]}");
                }
                String response = "{\"object\":\"list\",\"data\":[" + data + "],\"model\":\"text-embedding-3-small\"," +
                        "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
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
