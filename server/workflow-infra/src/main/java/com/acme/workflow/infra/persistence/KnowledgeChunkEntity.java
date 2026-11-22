package com.acme.workflow.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_knowledge_chunk")
public class KnowledgeChunkEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String knowledgeBaseId;
    @Column(nullable = false) private String documentId;
    @Column(nullable = false) private int chunkIndex;
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Column(nullable = false, columnDefinition = "TEXT") private String embeddingJson;
    @Column(nullable = false) private int dimensions;
    @Column(nullable = false) private Instant createdAt;

    protected KnowledgeChunkEntity() { }

    public KnowledgeChunkEntity(String knowledgeBaseId, String documentId, int chunkIndex, String content,
                                String embeddingJson, int dimensions, Instant now) {
        this.knowledgeBaseId = knowledgeBaseId; this.documentId = documentId; this.chunkIndex = chunkIndex;
        this.content = content; this.embeddingJson = embeddingJson; this.dimensions = dimensions;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public String getDocumentId() { return documentId; }
    public int getChunkIndex() { return chunkIndex; }
    public String getContent() { return content; }
    public String getEmbeddingJson() { return embeddingJson; }
    public int getDimensions() { return dimensions; }
    public Instant getCreatedAt() { return createdAt; }
}
