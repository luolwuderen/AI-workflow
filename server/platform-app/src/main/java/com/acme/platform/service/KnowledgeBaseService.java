package com.acme.platform.service;

import com.acme.platform.api.KnowledgeBaseView;
import com.acme.platform.api.KnowledgeDocumentDetailView;
import com.acme.platform.api.KnowledgeDocumentView;
import com.acme.platform.api.KnowledgeSearchHitView;
import com.acme.workflow.infra.knowledge.KnowledgeIngestionService;
import com.acme.workflow.infra.knowledge.KnowledgeQueryService;
import com.acme.workflow.infra.persistence.KnowledgeBaseEntity;
import com.acme.workflow.infra.persistence.KnowledgeBaseRepository;
import com.acme.workflow.infra.persistence.KnowledgeChunkRepository;
import com.acme.workflow.infra.persistence.KnowledgeDocumentEntity;
import com.acme.workflow.infra.persistence.KnowledgeDocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository bases;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeIngestionService ingestion;
    private final KnowledgeQueryService queries;
    private final int dimensions;

    public KnowledgeBaseService(KnowledgeBaseRepository bases, KnowledgeDocumentRepository documents,
                                KnowledgeChunkRepository chunks, KnowledgeIngestionService ingestion,
                                KnowledgeQueryService queries,
                                @Value("${spring.ai.openai.embedding.dimensions:1536}") int dimensions) {
        this.bases = bases; this.documents = documents; this.chunks = chunks; this.ingestion = ingestion;
        this.queries = queries;
        this.dimensions = dimensions;
    }

    public List<KnowledgeBaseView> list() {
        return bases.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    public KnowledgeBaseView create(String name, String description) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空。");
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(UUID.randomUUID().toString(), name.trim(),
                description == null ? "" : description, dimensions, Instant.now());
        return view(bases.saveAndFlush(entity));
    }

    @Transactional
    public void delete(String id) {
        base(id);
        List<String> documentIds = documents.findByKnowledgeBaseIdOrderByCreatedAtAsc(id).stream()
                .map(KnowledgeDocumentEntity::getId).toList();
        if (!documentIds.isEmpty()) {
            chunks.deleteByDocumentIdIn(documentIds);
            chunks.flush();
            documents.deleteAllInBatch(documents.findAllById(documentIds));
        }
        chunks.deleteByKnowledgeBaseId(id);
        chunks.flush();
        bases.deleteById(id);
    }

    public List<KnowledgeDocumentView> documents(String knowledgeBaseId) {
        base(knowledgeBaseId);
        return documents.findByKnowledgeBaseIdOrderByCreatedAtAsc(knowledgeBaseId).stream().map(this::view).toList();
    }

    public KnowledgeDocumentView addDocument(String knowledgeBaseId, String name, String content) {
        return view(ingestion.ingest(knowledgeBaseId, name, content));
    }

    public KnowledgeDocumentDetailView document(String knowledgeBaseId, String documentId) {
        base(knowledgeBaseId);
        KnowledgeDocumentEntity document = documents.findById(documentId)
                .filter(item -> knowledgeBaseId.equals(item.getKnowledgeBaseId()))
                .orElseThrow(() -> new NotFoundException("文档不存在：" + documentId));
        return new KnowledgeDocumentDetailView(document.getId(), document.getKnowledgeBaseId(), document.getName(),
                document.getContent(), document.getChunkCount(), document.getCreatedAt());
    }

    public List<KnowledgeSearchHitView> search(List<String> knowledgeBaseIds, String query, int topK,
                                               double threshold) {
        return queries.search(knowledgeBaseIds, query, topK, threshold).stream()
                .map(hit -> new KnowledgeSearchHitView(hit.knowledgeBaseId(), hit.documentId(), hit.documentName(),
                        hit.content(), hit.chunkIndex(), hit.score()))
                .toList();
    }

    @Transactional
    public void deleteDocument(String knowledgeBaseId, String documentId) {
        base(knowledgeBaseId);
        KnowledgeDocumentEntity document = documents.findById(documentId)
                .filter(item -> knowledgeBaseId.equals(item.getKnowledgeBaseId()))
                .orElseThrow(() -> new NotFoundException("文档不存在：" + documentId));
        chunks.deleteByDocumentIdIn(List.of(documentId));
        chunks.flush();
        documents.delete(document);
    }

    private KnowledgeBaseEntity base(String id) {
        return bases.findById(id).orElseThrow(() -> new NotFoundException("知识库不存在：" + id));
    }

    private KnowledgeBaseView view(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseView(entity.getId(), entity.getName(), entity.getDescription(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private KnowledgeDocumentView view(KnowledgeDocumentEntity entity) {
        return new KnowledgeDocumentView(entity.getId(), entity.getKnowledgeBaseId(), entity.getName(),
                entity.getChunkCount(), entity.getCreatedAt());
    }
}
