package com.acme.workflow.infra.knowledge;

import com.acme.workflow.infra.persistence.KnowledgeChunkEntity;
import com.acme.workflow.infra.persistence.KnowledgeChunkRepository;
import com.acme.workflow.infra.persistence.KnowledgeDocumentEntity;
import com.acme.workflow.infra.persistence.KnowledgeDocumentRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 向量检索：候选集上限固定，余弦相似度在内存中计算。 */
@Service
public class KnowledgeQueryService {
    private static final int CANDIDATE_LIMIT = 5000;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeDocumentRepository documents;
    private final EmbeddingModel embeddingModel;
    private final VectorCodec vectors;

    public KnowledgeQueryService(KnowledgeChunkRepository chunks, KnowledgeDocumentRepository documents,
                                 EmbeddingModel embeddingModel, VectorCodec vectors) {
        this.chunks = chunks; this.documents = documents;
        this.embeddingModel = embeddingModel; this.vectors = vectors;
    }

    public List<Hit> search(Collection<String> knowledgeBaseIds, String query, int topK, double threshold) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || query == null || query.isBlank())
            return List.of();
        int limit = Math.max(1, Math.min(20, topK));
        List<KnowledgeChunkEntity> candidates = chunks.findByKnowledgeBaseIdIn(knowledgeBaseIds,
                PageRequest.of(0, CANDIDATE_LIMIT + 1));
        if (candidates.size() > CANDIDATE_LIMIT)
            throw new IllegalStateException("候选向量超过 " + CANDIDATE_LIMIT + " 条，请缩小知识库范围。");
        if (candidates.isEmpty()) return List.of();
        float[] queryVector = embed(query);
        if (queryVector.length == 0) return List.of();

        Map<String, String> names = new HashMap<>();
        List<String> documentIds = candidates.stream().map(KnowledgeChunkEntity::getDocumentId).distinct().toList();
        for (KnowledgeDocumentEntity document : documents.findAllById(documentIds))
            names.put(document.getId(), document.getName());

        List<Hit> hits = new ArrayList<>();
        for (KnowledgeChunkEntity chunk : candidates) {
            if (chunk.getDimensions() != queryVector.length)
                throw new IllegalStateException("向量维度不一致：查询 " + queryVector.length
                        + "，知识块 " + chunk.getDimensions() + "。");
            float[] vector;
            try {
                vector = vectors.decode(chunk.getEmbeddingJson());
            } catch (RuntimeException error) {
                throw new IllegalStateException("知识块向量无法解析：" + chunk.getId(), error);
            }
            if (vector.length != queryVector.length)
                throw new IllegalStateException("向量维度不一致：查询 " + queryVector.length + "，知识块 " + vector.length + "。");
            double score = cosine(queryVector, vector);
            if (score < threshold) continue;
            hits.add(new Hit(chunk.getKnowledgeBaseId(), chunk.getDocumentId(),
                    names.getOrDefault(chunk.getDocumentId(), ""), chunk.getContent(), chunk.getChunkIndex(), score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return List.copyOf(hits.subList(0, Math.min(limit, hits.size())));
    }

    private float[] embed(String query) {
        List<float[]> embedded = embeddingModel.embed(List.of(query));
        return embedded == null || embedded.isEmpty() ? new float[0] : embedded.getFirst();
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public record Hit(String knowledgeBaseId, String documentId, String documentName, String content,
                      int chunkIndex, double score) { }
}
