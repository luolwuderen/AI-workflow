package com.acme.platform;

import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphEdge;
import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.Position;
import com.acme.workflow.infra.persistence.JsonCodec;
import com.acme.workflow.infra.persistence.NodeRunRepository;
import com.acme.workflow.infra.persistence.RunEntity;
import com.acme.workflow.infra.persistence.RunEventRepository;
import com.acme.workflow.infra.persistence.RunRepository;
import com.acme.workflow.runtime.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow-api-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.model.chat=none",
        "workflow.sse.heartbeat-interval=10ms"
})
@AutoConfigureMockMvc
class WorkflowApiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JsonCodec json;
    @Autowired private RunRepository runs;
    @Autowired private NodeRunRepository nodeRuns;
    @Autowired private RunEventRepository runEvents;
    @MockitoBean private ChatModel chatModel;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsAppAndPersistsSuccessfulAsynchronousRunAndEvents() throws Exception {
        mvc.perform(get("/api/v1/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(24)));

        String appId = createApp("API success");
        String runId = startRun(appId, "{\"inputs\":{\"text\":\"from api\"}}");
        Map<String, Object> completed = awaitTerminalRun(runId);

        assertThat(completed.get("status")).isEqualTo("SUCCEEDED");
        assertThat(completed.get("outputs")).isEqualTo(Map.of("message", "from api"));
        assertThat(completed.get("startedAt")).isNotNull();
        assertThat(completed.get("finishedAt")).isNotNull();
        assertThat(json.write(completed.get("nodes"))).contains("SUCCEEDED");

        mvc.perform(get("/api/v1/runs/{id}/events", runId).param("after", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", hasItems("RUN_STARTED", "NODE_STARTED", "NODE_SUCCEEDED", "RUN_SUCCEEDED")));
    }

    @Test
    void persistsInvalidInputAsFailedRun() throws Exception {
        String appId = createApp("API failure");
        String runId = startRun(appId, "{\"inputs\":{\"unknown\":\"value\"}}");

        Map<String, Object> completed = awaitTerminalRun(runId);

        assertThat(completed.get("status")).isEqualTo("FAILED");
        assertThat(completed.get("error").toString()).contains("未声明的运行输入");
        assertThat(json.write(completed.get("nodes"))).contains("FAILED");
        mvc.perform(get("/api/v1/runs/{id}/events", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", hasItems("NODE_FAILED", "RUN_FAILED")));
    }

    @Test
    void replaysOnlyEventsAfterTheRequestedSequenceOverSse() throws Exception {
        String appId = createApp("API SSE replay");
        String runId = startRun(appId, "{\"inputs\":{\"text\":\"from sse\"}}");
        awaitTerminalRun(runId);
        String eventJson = mvc.perform(get("/api/v1/runs/{id}/events", runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long firstSequence = mapper.readTree(eventJson).get(0).get("sequence").asLong();

        MvcResult pending = mvc.perform(get("/api/v1/runs/{id}/events/stream", runId)
                        .param("after", String.valueOf(firstSequence)))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(3000);
        String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("RUN_SUCCEEDED").contains("NODE_STARTED")
                .doesNotContain("id:" + firstSequence + "\n");
    }

    @Test
    void sendsHeartbeatWhileRunHasNoNewEvents() throws Exception {
        String appId = createApp("API SSE heartbeat");
        String app = mvc.perform(get("/api/v1/apps/{id}", appId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String runId = UUID.randomUUID().toString();
        runs.saveAndFlush(new RunEntity(runId, appId, json.write(json.map(app).get("graph")), "{}", Instant.now()));
        MvcResult pending = mvc.perform(get("/api/v1/runs/{id}/events/stream", runId))
                .andExpect(request().asyncStarted()).andReturn();

        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            String body = "";
            while (System.nanoTime() < deadline && !body.contains("heartbeat")) {
                body = pending.getResponse().getContentAsString();
                if (!body.contains("heartbeat")) Thread.sleep(25);
            }
            assertThat(body).contains("heartbeat");
        } finally {
            RunEntity run = runs.findById(runId).orElseThrow();
            run.finish(RunStatus.SUCCEEDED, "{}", null, Instant.now());
            runs.saveAndFlush(run);
            pending.getAsyncResult(3000);
        }
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
    }

    @Test
    void pausesAndResumesHumanInputThroughApi() throws Exception {
        String appId = createApp("API human input");
        GraphNode input = new GraphNode("user-input", "user-input", "Input", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "text", "label", "Text", "type", "text", "required", true))));
        GraphNode human = new GraphNode("human", "human-input", "Approval", new Position(100, 0),
                Map.of("prompt", "请处理 {{ user-input.text }}", "fields",
                        List.of(Map.of("variable", "note", "label", "处理意见", "type", "text", "required", true))));
        GraphNode output = new GraphNode("output", "output", "Output", new Position(200, 0),
                Map.of("mappings", List.of(Map.of("name", "note", "selector", "human.note"))));
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(input, human, output),
                List.of(new GraphEdge("e1", "user-input", "human", null, null),
                        new GraphEdge("e2", "human", "output", null, null)), Map.of());
        mvc.perform(put("/api/v1/apps/{id}/draft", appId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("graph", graph, "expectedVersion", 0))))
                .andExpect(status().isOk());
        String runId = startRun(appId, "{\"inputs\":{\"text\":\"from human\"}}");

        Map<String, Object> paused = awaitRunStatus(runId, "PAUSED");
        assertThat(json.write(paused.get("nodes"))).contains("WAITING").contains("请处理 from human");
        mvc.perform(post("/api/v1/runs/{id}/nodes/{nodeId}/resume", runId, "human")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inputs\":{}}"))
                .andExpect(status().isBadRequest());
        assertThat(awaitRunStatus(runId, "PAUSED").get("status")).isEqualTo("PAUSED");

        mvc.perform(post("/api/v1/runs/{id}/nodes/{nodeId}/resume", runId, "human")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inputs\":{\"note\":\"通过\"}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/runs/{id}/nodes/{nodeId}/resume", runId, "human")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inputs\":{\"note\":\"重复提交\"}}"))
                .andExpect(status().isConflict());

        Map<String, Object> completed = awaitTerminalRun(runId);
        assertThat(completed.get("status")).isEqualTo("SUCCEEDED");
        assertThat(completed.get("outputs")).isEqualTo(Map.of("note", "通过"));
        mvc.perform(get("/api/v1/runs/{id}/events", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", hasItems("NODE_WAITING", "RUN_PAUSED", "NODE_RESUMED", "RUN_RESUMED", "RUN_SUCCEEDED")));
    }

    @Test
    void deletesAppTogetherWithItsRunsNodesAndEvents() throws Exception {
        String appId = createApp("API delete");
        String runId = startRun(appId, "{\"inputs\":{\"text\":\"to delete\"}}");
        awaitTerminalRun(runId);
        assertThat(nodeRuns.findByRunIdOrderByStartedAtAsc(runId)).isNotEmpty();
        assertThat(runEvents.findByRunIdOrderByIdAsc(runId)).isNotEmpty();

        mvc.perform(delete("/api/v1/apps/{id}", appId)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/apps/{id}", appId)).andExpect(status().isNotFound());
        assertThat(runs.findById(runId)).isEmpty();
        assertThat(nodeRuns.findByRunIdOrderByStartedAtAsc(runId)).isEmpty();
        assertThat(runEvents.findByRunIdOrderByIdAsc(runId)).isEmpty();
    }

    private String createApp(String name) throws Exception {
        String response = mvc.perform(post("/api/v1/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"appType\":\"WORKFLOW\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        return String.valueOf(json.map(response).get("id"));
    }

    private String startRun(String appId, String body) throws Exception {
        String response = mvc.perform(post("/api/v1/apps/{id}/runs", appId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        return String.valueOf(json.map(response).get("id"));
    }

    private Map<String, Object> awaitTerminalRun(String runId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Map<String, Object> run = Map.of();
        while (System.nanoTime() < deadline) {
            String response = mvc.perform(get("/api/v1/runs/{id}", runId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            run = json.map(response);
            if ("SUCCEEDED".equals(run.get("status")) || "FAILED".equals(run.get("status"))) return run;
            Thread.sleep(25);
        }
        throw new AssertionError("运行未在 10 秒内结束：" + run);
    }

    private Map<String, Object> awaitRunStatus(String runId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Map<String, Object> run = Map.of();
        while (System.nanoTime() < deadline) {
            String response = mvc.perform(get("/api/v1/runs/{id}", runId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            run = json.map(response);
            if (expected.equals(run.get("status"))) return run;
            Thread.sleep(25);
        }
        throw new AssertionError("运行未在 10 秒内进入 " + expected + "：" + run);
    }
}
