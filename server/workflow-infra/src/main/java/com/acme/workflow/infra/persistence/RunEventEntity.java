package com.acme.workflow.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_run_event", indexes = @Index(name = "idx_event_run_sequence", columnList = "runId,id"))
public class RunEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String runId;
    @Column(nullable = false) private String eventType;
    private String nodeId;
    @Column(nullable = false, columnDefinition = "TEXT") private String dataJson;
    @Column(nullable = false) private Instant createdAt;

    protected RunEventEntity() { }

    public RunEventEntity(String runId, String eventType, String nodeId, String dataJson, Instant createdAt) {
        this.runId = runId; this.eventType = eventType; this.nodeId = nodeId; this.dataJson = dataJson; this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public String getEventType() { return eventType; }
    public String getNodeId() { return nodeId; }
    public String getDataJson() { return dataJson; }
    public Instant getCreatedAt() { return createdAt; }
}
