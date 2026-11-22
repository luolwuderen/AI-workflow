package com.acme.workflow.infra.execution;

import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.Position;
import com.acme.workflow.infra.execution.agent.CalculatorAgentTool;
import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutorTest {

    @Test
    void loopsOverToolCallsAndReturnsFinalAnswer() {
        ScriptedChatModel model = new ScriptedChatModel(
                assistantWithToolCall("call_1", "calculator", "{\"expression\":\"6 * 7\"}"),
                assistant("The result is 42"));
        AgentExecutor executor = new AgentExecutor(model, List.of(new CalculatorAgentTool()));
        List<Map<String, Object>> events = new ArrayList<>();

        Map<String, Object> result = executor.execute(context(executorConfig(Map.of("tools", List.of("calculator"))), events));

        assertThat(result).containsEntry("text", "The result is 42");
        assertThat(result).containsEntry("iterations", 2);
        assertThat(model.calls).hasSize(2);
        // 模型收到的请求里携带了 calculator 工具定义
        ToolCallingChatOptions opts = (ToolCallingChatOptions) model.lastPrompt.getOptions();
        assertThat(opts.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("calculator");
        assertThat(events).anySatisfy(event -> {
            assertThat(event).containsEntry("name", "calculator");
            assertThat(String.valueOf(event.get("arguments"))).contains("6 * 7");
            assertThat(String.valueOf(event.get("result"))).contains("42");
        });
        // 回填给模型的第二轮消息包含工具结果
        Prompt second = model.calls.get(1);
        assertThat(second.getInstructions().toString()).contains("42");
    }

    @Test
    void degradesToPlainAnswerWhenNoToolsConfigured() {
        ScriptedChatModel model = new ScriptedChatModel(assistant("直接回答"));
        AgentExecutor executor = new AgentExecutor(model, List.of(new CalculatorAgentTool()));

        Map<String, Object> result = executor.execute(context(executorConfig(Map.of("tools", List.of())), new ArrayList<>()));

        assertThat(result).containsEntry("text", "直接回答");
        assertThat(result).containsEntry("iterations", 1);
        ToolCallingChatOptions opts = (ToolCallingChatOptions) model.lastPrompt.getOptions();
        assertThat(opts.getToolCallbacks()).isEmpty();
    }

    @Test
    void rejectsUnknownConfiguredTool() {
        AgentExecutor executor = new AgentExecutor(new ScriptedChatModel(assistant("x")), List.of(new CalculatorAgentTool()));

        assertThatThrownBy(() -> executor.execute(context(executorConfig(Map.of("tools", List.of("unknown-tool"))), new ArrayList<>())))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("未知工具");
    }

    @Test
    void stopsAtMaxIterationsWhenModelKeepsCallingTools() {
        ScriptedChatModel model = new ScriptedChatModel(
                assistantWithToolCall("c1", "calculator", "{\"expression\":\"1+1\"}"),
                assistantWithToolCall("c2", "calculator", "{\"expression\":\"2+2\"}"),
                assistantWithToolCall("c3", "calculator", "{\"expression\":\"3+3\"}"));
        AgentExecutor executor = new AgentExecutor(model, List.of(new CalculatorAgentTool()));
        Map<String, Object> config = executorConfig(Map.of("tools", List.of("calculator"), "maxIterations", 2));

        Map<String, Object> result = executor.execute(context(config, new ArrayList<>()));

        assertThat(result).containsEntry("iterations", 2);
        assertThat(model.calls).hasSize(2);
    }

    private static Map<String, Object> executorConfig(Map<String, Object> overrides) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("model", "test-model");
        config.put("instruction", "Compute {{start.text}}");
        config.put("strategy", "function_call");
        config.put("tools", List.of("calculator"));
        config.put("maxIterations", 5);
        config.putAll(overrides);
        return config;
    }

    private static NodeExecutionContext context(Map<String, Object> config, List<Map<String, Object>> events) {
        GraphNode node = new GraphNode("agent", "agent", "Agent", new Position(0, 0), config);
        return new NodeExecutionContext("run", null, node, Map.of("text", "anything"),
                Map.of("start", Map.of("text", "anything")), new LinkedHashMap<>(),
                (type, data) -> { if ("AGENT_TOOL_CALL".equals(type)) events.add(new LinkedHashMap<>(data)); });
    }

    private static AssistantMessage assistant(String text) {
        return AssistantMessage.builder().content(text).build();
    }

    private static AssistantMessage assistantWithToolCall(String id, String name, String arguments) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final AtomicInteger index = new AtomicInteger();
        final List<Prompt> calls = new ArrayList<>();
        volatile Prompt lastPrompt;

        ScriptedChatModel(AssistantMessage... messages) {
            this.responses = new ArrayList<>();
            for (AssistantMessage message : messages) {
                this.responses.add(new ChatResponse(List.of(new Generation(message))));
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.add(prompt);
            lastPrompt = prompt;
            int i = index.getAndIncrement();
            return responses.get(Math.min(i, responses.size() - 1));
        }
    }
}
