package com.acme.workflow.infra.persistence;

import com.acme.workflow.core.model.AppType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "workflow_app")
public class AppEntity {
    @Id private String id;
    @Column(nullable = false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AppType appType;
    @Column(nullable = false, columnDefinition = "TEXT") private String draftJson;
    @Version private long version;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected AppEntity() { }

    public AppEntity(String id, String name, AppType appType, String draftJson, Instant now) {
        this.id = id; this.name = name; this.appType = appType; this.draftJson = draftJson;
        this.createdAt = now; this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public AppType getAppType() { return appType; }
    public String getDraftJson() { return draftJson; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void updateDraft(String graph, Instant now) { this.draftJson = graph; this.updatedAt = now; }
}
