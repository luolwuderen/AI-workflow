package com.acme.workflow.infra.execution;

import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问题分类器：调用 ChatModel 把输入归入配置的类别，输出 branch=class id。
 * 解析失败直接报错，不猜测兜底。
 */
@Component
public class QuestionClassifierExecutor implements NodeExecutor {
    private final ChatModel chatModel;

    public QuestionClassifierExecutor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String type() { return "question-classifier"; }

    @Override
    public Map<String, Object> execute(NodeExecutionContext context) {
        try {
            Map<String, Object> config = context.node().config();
            List<Map<String, String>> classes = classes(config.get("classes"));
            if (classes.isEmpty()) throw new IllegalArgumentException("至少配置一个类别。");
            String query = context.render(String.valueOf(config.getOrDefault("query", "")));
            String instruction = String.valueOf(config.getOrDefault("instruction", ""));
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .model(String.valueOf(config.getOrDefault("model", "")));
            if (config.get("temperature") instanceof Number value) options.temperature(value.doubleValue());
            if (config.get("topP") instanceof Number value) options.topP(value.doubleValue());
            if (config.get("maxTokens") instanceof Number value) options.maxTokens(value.intValue());
            Message message = new UserMessage(prompt(instruction, classes, query));
            ChatResponse response = chatModel.call(new Prompt(List.of(message), options.build()));
            String raw = response.getResult().getOutput().getText();
            Map<String, String> matched = match(String.valueOf(raw), classes);
            if (matched == null)
                throw new IllegalArgumentException("无法从模型输出中解析类别 id：" + raw);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("branch", matched.get("id"));
            output.put("class_name", matched.get("name"));
            output.put("usage", Map.of());
            return output;
        } catch (NodeExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new NodeExecutionException(context.node().id(), "问题分类失败：" + error.getMessage(), error);
        }
    }

    private String prompt(String instruction, List<Map<String, String>> classes, String query) {
        StringBuilder prompt = new StringBuilder("你是问题分类器。请把用户输入归入下列类别之一，并只输出类别 id，不要输出任何解释。\n");
        for (Map<String, String> item : classes) {
            prompt.append("- id=").append(item.get("id")).append(" 名称=").append(item.get("name")).append('\n');
        }
        if (!instruction.isBlank()) prompt.append("额外要求：").append(instruction).append('\n');
        prompt.append("用户输入：\n").append(query);
        return prompt.toString();
    }

    private List<Map<String, String>> classes(Object raw) {
        List<Map<String, String>> classes = new ArrayList<>();
        if (!(raw instanceof List<?> configured)) return classes;
        for (Object item : configured) {
            if (!(item instanceof Map<?, ?> one)) continue;
            String id = String.valueOf(one.get("id"));
            String name = String.valueOf(one.get("name"));
            if (id.isBlank() || "null".equals(id) || name.isBlank() || "null".equals(name)) continue;
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("name", name);
            classes.add(entry);
        }
        return classes;
    }

    private Map<String, String> match(String raw, List<Map<String, String>> classes) {
        String value = normalize(raw);
        if (value.isEmpty()) return null;
        for (Map<String, String> item : classes) {
            if (item.get("id").equalsIgnoreCase(value)) return item;
        }
        for (Map<String, String> item : classes) {
            if (item.get("name").equalsIgnoreCase(value)) return item;
        }
        return null;
    }

    private String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) {
            int firstBreak = value.indexOf('\n');
            value = firstBreak < 0 ? "" : value.substring(firstBreak + 1);
            int end = value.lastIndexOf("```");
            if (end >= 0) value = value.substring(0, end);
        }
        value = value.trim();
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
