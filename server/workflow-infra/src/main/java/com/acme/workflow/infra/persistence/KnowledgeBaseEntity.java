package com.acme.workflow.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_knowledge_base")
public class KnowledgeBaseEntity {
    @Id private String id;
    @Column(nullable = false) private String name;
    @Column(nullable = false, columnDefinition = "TEXT") private String description;
    @Column(nullable = false) private int dimensions;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected KnowledgeBaseEntity() { }

    public KnowledgeBaseEntity(String id, String name, String description, int dimensions, Instant now) {
        this.id = id; this.name = name; this.description = description; this.dimensions = dimensions;
        this.createdAt = now; this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getDimensions() { return dimensions; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
