package com.acme.workflow.infra.knowledge;

import com.acme.workflow.infra.persistence.KnowledgeBaseEntity;
import com.acme.workflow.infra.persistence.KnowledgeBaseRepository;
import com.acme.workflow.infra.persistence.KnowledgeChunkEntity;
import com.acme.workflow.infra.persistence.KnowledgeChunkRepository;
import com.acme.workflow.infra.persistence.KnowledgeDocumentEntity;
import com.acme.workflow.infra.persistence.KnowledgeDocumentRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 文档入库：先切块并完成 embedding，再一次性落库。 */
@Service
public class KnowledgeIngestionService {
    private static final int BATCH_SIZE = 64;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final EmbeddingModel embeddingModel;
    private final VectorCodec vectors;

    public KnowledgeIngestionService(KnowledgeBaseRepository bases, KnowledgeDocumentRepository documents,
                                     KnowledgeChunkRepository chunks, EmbeddingModel embeddingModel,
                                     VectorCodec vectors) {
        this.bases = bases; this.documents = documents; this.chunks = chunks;
        this.embeddingModel = embeddingModel; this.vectors = vectors;
    }

    @Transactional
    public KnowledgeDocumentEntity ingest(String knowledgeBaseId, String name, String content) {
        KnowledgeBaseEntity base = bases.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在：" + knowledgeBaseId));
        if (name == null || name.isBlank()) throw new IllegalArgumentException("文档名称不能为空。");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("文档内容不能为空。");
        List<String> parts = TextChunker.split(content);
        if (parts.isEmpty()) throw new IllegalArgumentException("文档内容不能为空。");

        List<String> encoded = new ArrayList<>(parts.size());
        for (int start = 0; start < parts.size(); start += BATCH_SIZE) {
            List<String> batch = parts.subList(start, Math.min(parts.size(), start + BATCH_SIZE));
            List<float[]> embedded = embeddingModel.embed(batch);
            if (embedded == null || embedded.size() != batch.size())
                throw new IllegalStateException("embedding 返回数量与文本块数量不一致。");
            for (float[] vector : embedded) {
                if (vector.length != base.getDimensions())
                    throw new IllegalStateException("embedding 维度与知识库不一致：期望 " + base.getDimensions()
                            + "，实际 " + vector.length + "。");
                encoded.add(vectors.encode(vector));
            }
        }

        String documentId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        KnowledgeDocumentEntity document = documents.save(
                new KnowledgeDocumentEntity(documentId, knowledgeBaseId, name.trim(), content, parts.size(), now));
        List<KnowledgeChunkEntity> entities = new ArrayList<>(parts.size());
        for (int index = 0; index < parts.size(); index++) {
            entities.add(new KnowledgeChunkEntity(knowledgeBaseId, documentId, index, parts.get(index),
                    encoded.get(index), base.getDimensions(), now));
        }
        chunks.saveAll(entities);
        return document;
    }
}
