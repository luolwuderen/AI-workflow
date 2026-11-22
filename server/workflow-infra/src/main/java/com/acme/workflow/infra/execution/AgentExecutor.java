package com.acme.workflow.infra.execution;

import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import com.acme.workflow.runtime.agent.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 节点执行器：function_call 手动工具调用循环。
 * 每轮调用模型，若返回 tool_calls 则执行对应内置工具并回填结果，直到模型给出终答或达到 maxIterations。
 */
@Component
public class AgentExecutor implements NodeExecutor {

    static final String STRATEGY = "function_call";

    private final ChatModel chatModel;
    private final Map<String, AgentTool> availableTools;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentExecutor(ChatModel chatModel, List<AgentTool> tools) {
        this.chatModel = chatModel;
        this.availableTools = new LinkedHashMap<>();
        for (AgentTool tool : tools) availableTools.put(tool.name(), tool);
    }

    @Override
    public String type() {
        return "agent";
    }

    @Override
    public Map<String, Object> execute(NodeExecutionContext context) {
        try {
            Map<String, Object> config = context.node().config();
            String strategy = String.valueOf(config.getOrDefault("strategy", STRATEGY));
            if (!STRATEGY.equals(strategy)) {
                throw new IllegalArgumentException("Agent 仅支持 strategy=function_call，当前为 " + strategy + "。");
            }
            List<Message> messages = new ArrayList<>();
            messages.add(new UserMessage(context.render(String.valueOf(config.getOrDefault("instruction", "")))));

            List<String> requested = toolNames(config.get("tools"));
            List<ToolCallback> callbacks = new ArrayList<>();
            Map<String, AgentTool> enabled = new LinkedHashMap<>();
            for (String name : requested) {
                AgentTool tool = availableTools.get(name);
                if (tool == null) throw new IllegalArgumentException("Agent 配置了未知工具：" + name + "。");
                enabled.put(name, tool);
                callbacks.add(toCallback(tool));
            }

            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .model(String.valueOf(config.getOrDefault("model", "")))
                    .toolCallbacks(callbacks);
            if (config.get("temperature") instanceof Number value) options.temperature(value.doubleValue());
            if (config.get("topP") instanceof Number value) options.topP(value.doubleValue());
            if (config.get("maxTokens") instanceof Number value) options.maxTokens(value.intValue());

            int maxIterations = clamp(config.get("maxIterations"), 1, 20, 5);
            int iterations = 0;
            String text = "";
            while (iterations < maxIterations) {
                iterations++;
                ChatResponse response = chatModel.call(new Prompt(new ArrayList<>(messages), options.build()));
                AssistantMessage assistant = response.getResult().getOutput();
                messages.add(assistant);
                if (assistant.getText() != null) text = assistant.getText();
                List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
                if (calls == null || calls.isEmpty()) break;

                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                for (AssistantMessage.ToolCall call : calls) {
                    String name = call.name();
                    AgentTool tool = enabled.get(name);
                    String result;
                    if (tool == null) {
                        result = "{\"error\":\"未知工具：" + name + "\"}";
                    } else {
                        try {
                            result = tool.call(call.arguments());
                        } catch (Exception error) {
                            result = "{\"error\":\"工具执行失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()) + "\"}";
                        }
                    }
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("iteration", iterations);
                    event.put("name", name);
                    event.put("arguments", call.arguments());
                    event.put("result", result);
                    context.emit("AGENT_TOOL_CALL", event);
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), name, result));
                }
                messages.add(ToolResponseMessage.builder().responses(responses).build());
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("text", text);
            output.put("iterations", iterations);
            output.put("usage", Map.of());
            return output;
        } catch (Exception error) {
            throw new NodeExecutionException(context.node().id(), "Agent 调用失败：" + error.getMessage(), error);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toolNames(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> names = new ArrayList<>();
        for (Object item : list) names.add(String.valueOf(item));
        return names;
    }

    private FunctionToolCallback<Map<String, Object>, String> toCallback(AgentTool tool) {
        return FunctionToolCallback
                .<Map<String, Object>, String>builder(tool.name(), (args, ctx) -> {
                    try {
                        return tool.call(mapper.writeValueAsString(args));
                    } catch (Exception error) {
                        return "{\"error\":\"工具执行失败：" + error.getMessage() + "\"}";
                    }
                })
                .description(tool.description())
                .inputType(Map.class)
                .inputSchema(toJson(tool.inputSchema()))
                .toolCallResultConverter((result, type) -> result == null ? "" : result.toString())
                .build();
    }

    private String toJson(Map<String, Object> schema) {
        try {
            return mapper.writeValueAsString(schema);
        } catch (Exception error) {
            return "{}";
        }
    }

    private int clamp(Object raw, int min, int max, int fallback) {
        int value = raw instanceof Number number ? number.intValue() : fallback;
        return Math.max(min, Math.min(max, value));
    }
}
