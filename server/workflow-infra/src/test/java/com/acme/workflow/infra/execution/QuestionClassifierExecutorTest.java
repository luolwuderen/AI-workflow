package com.acme.workflow.infra.execution;

import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.Position;
import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionClassifierExecutorTest {

    @Test
    void matchesClassIdAndReturnsBranch() {
        QuestionClassifierExecutor executor = new QuestionClassifierExecutor(new ScriptedChatModel("2"));

        Map<String, Object> result = executor.execute(context(config()));

        assertThat(result).containsEntry("branch", "2");
        assertThat(result).containsEntry("class_name", "其他");
    }

    @Test
    void fallsBackToClassNameMatch() {
        QuestionClassifierExecutor executor = new QuestionClassifierExecutor(new ScriptedChatModel("数学"));

        Map<String, Object> result = executor.execute(context(config()));

        assertThat(result).containsEntry("branch", "1");
    }

    @Test
    void toleratesQuotesAndCodeFences() {
        QuestionClassifierExecutor executor = new QuestionClassifierExecutor(new ScriptedChatModel("```\n\"1\"\n```"));

        Map<String, Object> result = executor.execute(context(config()));

        assertThat(result).containsEntry("branch", "1");
    }

    @Test
    void failsWhenModelOutputCannotBeMatched() {
        QuestionClassifierExecutor executor = new QuestionClassifierExecutor(new ScriptedChatModel("我不知道"));

        assertThatThrownBy(() -> executor.execute(context(config())))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("无法从模型输出中解析类别 id");
    }

    @Test
    void failsWhenNoClassesConfigured() {
        QuestionClassifierExecutor executor = new QuestionClassifierExecutor(new ScriptedChatModel("1"));
        Map<String, Object> config = config();
        config.put("classes", List.of());

        assertThatThrownBy(() -> executor.execute(context(config)))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("类别");
    }

    private static Map<String, Object> config() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("model", "test-model");
        config.put("query", "{{start.text}}");
        config.put("classes", List.of(
                new LinkedHashMap<>(Map.of("id", "1", "name", "数学")),
                new LinkedHashMap<>(Map.of("id", "2", "name", "其他"))));
        config.put("instruction", "");
        config.put("temperature", 0.2);
        config.put("topP", 1.0);
        config.put("maxTokens", 1024);
        return config;
    }

    private static NodeExecutionContext context(Map<String, Object> config) {
        GraphNode node = new GraphNode("classifier", "question-classifier", "Classifier", new Position(0, 0), config);
        return new NodeExecutionContext("run", null, node, Map.of("text", "两个苹果加三个苹果"),
                Map.of("start", Map.of("text", "两个苹果加三个苹果")), new LinkedHashMap<>(),
                (type, data) -> { });
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final AtomicInteger index = new AtomicInteger();
        final List<Prompt> calls = new ArrayList<>();

        ScriptedChatModel(String... texts) {
            this.responses = new ArrayList<>();
            for (String text : texts) {
                this.responses.add(new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content(text).build()))));
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.add(prompt);
            int position = Math.min(index.getAndIncrement(), responses.size() - 1);
            return responses.get(position);
        }
    }
}
