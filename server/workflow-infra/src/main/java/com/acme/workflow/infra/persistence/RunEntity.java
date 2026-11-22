package com.acme.workflow.infra.persistence;

import com.acme.workflow.runtime.RunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_run")
public class RunEntity {
    @Id private String id;
    @Column(nullable = false) private String appId;
    @Column(nullable = false, columnDefinition = "TEXT") private String graphJson;
    @Column(nullable = false, columnDefinition = "TEXT") private String inputsJson;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private RunStatus status;
    @Column(columnDefinition = "TEXT") private String outputsJson;
    @Column(columnDefinition = "TEXT") private String error;
    @Column(nullable = false) private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    protected RunEntity() { }

    public RunEntity(String id, String appId, String graphJson, String inputsJson, Instant createdAt) {
        this.id = id; this.appId = appId; this.graphJson = graphJson; this.inputsJson = inputsJson;
        this.status = RunStatus.QUEUED; this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getAppId() { return appId; }
    public String getGraphJson() { return graphJson; }
    public String getInputsJson() { return inputsJson; }
    public RunStatus getStatus() { return status; }
    public String getOutputsJson() { return outputsJson; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void start(Instant now) { this.status = RunStatus.RUNNING; this.startedAt = now; }
    public void pause() { this.status = RunStatus.PAUSED; }
    public void resume() { this.status = RunStatus.RUNNING; this.finishedAt = null; }
    public void finish(RunStatus status, String outputsJson, String error, Instant now) {
        this.status = status; this.outputsJson = outputsJson; this.error = error; this.finishedAt = now;
    }
}
