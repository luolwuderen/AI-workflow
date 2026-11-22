package com.acme.workflow.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_knowledge_document")
public class KnowledgeDocumentEntity {
    @Id private String id;
    @Column(nullable = false) private String knowledgeBaseId;
    @Column(nullable = false) private String name;
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Column(nullable = false) private int chunkCount;
    @Column(nullable = false) private Instant createdAt;

    protected KnowledgeDocumentEntity() { }

    public KnowledgeDocumentEntity(String id, String knowledgeBaseId, String name, String content, int chunkCount,
                                   Instant now) {
        this.id = id; this.knowledgeBaseId = knowledgeBaseId; this.name = name; this.content = content;
        this.chunkCount = chunkCount; this.createdAt = now;
    }

    public String getId() { return id; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public String getName() { return name; }
    public String getContent() { return content; }
    public int getChunkCount() { return chunkCount; }
    public Instant getCreatedAt() { return createdAt; }
}
