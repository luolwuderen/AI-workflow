package com.acme.workflow.infra.execution;

import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SpringAiLlmExecutor implements NodeExecutor {
    private final ChatModel chatModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpringAiLlmExecutor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String type() { return "llm"; }

    @Override
    public Map<String, Object> execute(NodeExecutionContext context) {
        try {
            Map<String, Object> config = context.node().config();
            List<Message> messages = new ArrayList<>();
            Object rawMessages = config.get("messages");
            if (!(rawMessages instanceof List<?> configured) || configured.isEmpty()) throw new IllegalArgumentException("messages 至少配置一条。");
            for (Object raw : configured) {
                if (!(raw instanceof Map<?, ?> item)) throw new IllegalArgumentException("消息必须为对象。");
                String role = String.valueOf(item.get("role"));
                String content = context.render(String.valueOf(item.containsKey("content") ? item.get("content") : ""));
                switch (role) {
                    case "system" -> messages.add(new SystemMessage(content));
                    case "user" -> messages.add(new UserMessage(content));
                    case "assistant" -> messages.add(new AssistantMessage(content));
                    default -> throw new IllegalArgumentException("不支持的消息角色：" + role);
                }
            }
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(String.valueOf(config.get("model")));
            if (config.get("temperature") instanceof Number value) options.temperature(value.doubleValue());
            if (config.get("topP") instanceof Number value) options.topP(value.doubleValue());
            if (config.get("maxTokens") instanceof Number value) options.maxTokens(value.intValue());
            if (config.get("stop") instanceof List<?> stop) options.stop(stop.stream().map(String::valueOf).toList());
            String responseFormat = String.valueOf(config.getOrDefault("responseFormat", "text"));
            OpenAiChatModel.ResponseFormat.Builder format = OpenAiChatModel.ResponseFormat.builder()
                    .type(OpenAiChatModel.ResponseFormat.Type.valueOf(responseFormat.toUpperCase()));
            if ("json_schema".equals(responseFormat)) {
                if (!(config.get("jsonSchema") instanceof Map<?, ?> schema))
                    throw new IllegalArgumentException("responseFormat 为 json_schema 时必须配置 jsonSchema 对象。");
                format.jsonSchema(mapper.writeValueAsString(schema)).strict(true);
            }
            options.responseFormat(format.build());
            Prompt prompt = new Prompt(messages, options.build());
            StringBuilder text = new StringBuilder();
            if (Boolean.FALSE.equals(config.get("stream"))) {
                ChatResponse response = chatModel.call(prompt);
                String delta = response.getResult().getOutput().getText();
                if (delta != null) text.append(delta);
            } else {
                for (ChatResponse response : chatModel.stream(prompt).toIterable()) {
                    if (response.getResult() == null || response.getResult().getOutput() == null) continue;
                    String delta = response.getResult().getOutput().getText();
                    if (delta != null && !delta.isEmpty()) {
                        text.append(delta);
                        context.emit("LLM_DELTA", Map.of("delta", delta));
                    }
                }
            }
            return Map.of("text", text.toString());
        } catch (Exception error) {
            throw new NodeExecutionException(context.node().id(), "LLM 调用失败：" + error.getMessage(), error);
        }
    }
}
