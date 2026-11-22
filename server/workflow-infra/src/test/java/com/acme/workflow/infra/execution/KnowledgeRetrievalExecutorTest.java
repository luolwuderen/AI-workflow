package com.acme.workflow.infra.execution;

import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.Position;
import com.acme.workflow.infra.knowledge.KnowledgeQueryService;
import com.acme.workflow.infra.knowledge.VectorCodec;
import com.acme.workflow.infra.persistence.KnowledgeChunkEntity;
import com.acme.workflow.infra.persistence.KnowledgeChunkRepository;
import com.acme.workflow.infra.persistence.KnowledgeDocumentEntity;
import com.acme.workflow.infra.persistence.KnowledgeDocumentRepository;
import com.acme.workflow.runtime.NodeExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalExecutorTest {
    private final VectorCodec vectors = new VectorCodec();

    @Test
    void returnsMatchingChunksSortedBySimilarityAndRenderedContext() {
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        Map<String, float[]> embedded = new HashMap<>();
        embedded.put("苹果", new float[]{1, 0});
        KnowledgeQueryService queryService = new KnowledgeQueryService(chunks, documents,
                new FakeEmbeddingModel(embedded), vectors);

        when(chunks.findByKnowledgeBaseIdIn(ArgumentMatchers.anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(
                        chunk("kb", "doc-a", 0, "苹果是一种水果", new float[]{0.8f, 0.2f}),
                        chunk("kb", "doc-b", 0, "香蕉是黄色的", new float[]{0.1f, 0.9f}),
                        chunk("kb", "doc-c", 1, "苹果派很好吃", new float[]{1, 0})));
        when(documents.findAllById(ArgumentMatchers.<Iterable<String>>any())).thenReturn(List.of(
                new KnowledgeDocumentEntity("doc-a", "kb", "水果", "苹果是一种水果", 1, Instant.now()),
                new KnowledgeDocumentEntity("doc-b", "kb", "其他", "香蕉是黄色的", 1, Instant.now()),
                new KnowledgeDocumentEntity("doc-c", "kb", "甜点", "苹果派很好吃", 1, Instant.now())));

        Map<String, Object> output = new KnowledgeRetrievalExecutor(queryService)
                .execute(context(Map.of("datasetIds", List.of("kb"), "query", "{{start.text}}",
                        "topK", 2, "scoreThreshold", 0.5), "苹果"));

        assertThat(output.get("count")).isEqualTo(2);
        assertThat(String.valueOf(output.get("context")))
                .startsWith("苹果派很好吃").contains("苹果是一种水果").doesNotContain("香蕉是黄色的");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) output.get("result");
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().get("documentName")).isEqualTo("甜点");
        assertThat((Double) result.getFirst().get("score")).isGreaterThan((Double) result.get(1).get("score"));
    }

    @Test
    void returnsEmptyResultWhenNothingMeetsThreshold() {
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        Map<String, float[]> embedded = new HashMap<>();
        embedded.put("苹果", new float[]{0.6f, 0.8f});
        KnowledgeQueryService queryService = new KnowledgeQueryService(chunks, documents,
                new FakeEmbeddingModel(embedded), vectors);

        when(chunks.findByKnowledgeBaseIdIn(ArgumentMatchers.anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(chunk("kb", "doc-a", 0, "苹果是一种水果", new float[]{1, 0})));
        when(documents.findAllById(ArgumentMatchers.<Iterable<String>>any())).thenReturn(List.of());

        Map<String, Object> output = new KnowledgeRetrievalExecutor(queryService)
                .execute(context(Map.of("datasetIds", List.of("kb"), "query", "{{start.text}}",
                        "topK", 4, "scoreThreshold", 0.9), "苹果"));

        assertThat(output.get("count")).isEqualTo(0);
        assertThat(output.get("context")).isEqualTo("");
        assertThat((List<?>) output.get("result")).isEmpty();
    }

    @Test
    void returnsEmptyResultWhenKnowledgeBaseHasNoChunks() {
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeQueryService queryService = new KnowledgeQueryService(chunks, documents,
                new FakeEmbeddingModel(Map.of("苹果", new float[]{1, 0})), vectors);
        when(chunks.findByKnowledgeBaseIdIn(ArgumentMatchers.anyCollection(), any(Pageable.class))).thenReturn(List.of());

        Map<String, Object> output = new KnowledgeRetrievalExecutor(queryService)
                .execute(context(Map.of("datasetIds", List.of("kb"), "query", "{{start.text}}"), "苹果"));

        assertThat(output.get("count")).isEqualTo(0);
        assertThat(output.get("context")).isEqualTo("");
    }

    private KnowledgeChunkEntity chunk(String kb, String documentId, int sequence, String content, float[] vector) {
        return new KnowledgeChunkEntity(kb, documentId, sequence, content, vectors.encode(vector), vector.length,
                Instant.now());
    }

    private NodeExecutionContext context(Map<String, Object> config, String input) {
        GraphNode start = new GraphNode("start", "user-input", "Start", new Position(0, 0),
                Map.of("fields", List.of(Map.of("variable", "text", "label", "Text", "type", "text"))));
        GraphNode retrieval = new GraphNode("retrieval", "knowledge-retrieval", "Retrieval",
                new Position(200, 0), config);
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(start, retrieval), List.of(), Map.of());
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        outputs.put("start", Map.of("text", input));
        return new NodeExecutionContext("run", graph, retrieval, Map.of(), outputs, Map.of(), (type, data) -> { });
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        private final Map<String, float[]> vectors;

        private FakeEmbeddingModel(Map<String, float[]> vectors) {
            this.vectors = vectors;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public float[] embed(Document document) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            List<float[]> result = new ArrayList<>(texts.size());
            for (String text : texts) result.add(vectors.getOrDefault(text, new float[]{0, 0}));
            return result;
        }
    }
}
