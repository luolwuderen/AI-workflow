package com.acme.workflow.infra.persistence;

import com.acme.workflow.runtime.NodeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_node_run")
public class NodeRunEntity {
    @Id private String id;
    @Column(nullable = false) private String runId;
    @Column(nullable = false) private String nodeId;
    @Column(nullable = false) private String nodeType;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private NodeStatus status;
    @Column(columnDefinition = "TEXT") private String inputsJson;
    @Column(columnDefinition = "TEXT") private String outputsJson;
    @Column(columnDefinition = "TEXT") private String error;
    private long durationMillis;
    @Column(nullable = false) private Instant startedAt;
    private Instant finishedAt;

    protected NodeRunEntity() { }

    public NodeRunEntity(String runId, String nodeId, String nodeType, String inputsJson, Instant now) {
        this.id = runId + ":" + nodeId; this.runId = runId; this.nodeId = nodeId; this.nodeType = nodeType;
        this.inputsJson = inputsJson; this.status = NodeStatus.RUNNING; this.startedAt = now;
    }

    public String getRunId() { return runId; }
    public String getNodeId() { return nodeId; }
    public String getNodeType() { return nodeType; }
    public NodeStatus getStatus() { return status; }
    public String getInputsJson() { return inputsJson; }
    public String getOutputsJson() { return outputsJson; }
    public String getError() { return error; }
    public long getDurationMillis() { return durationMillis; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void waitForInput(String inputsJson) {
        this.status = NodeStatus.WAITING; this.inputsJson = inputsJson; this.outputsJson = null;
        this.error = null; this.finishedAt = null;
    }
    public void resume() { this.status = NodeStatus.RUNNING; this.finishedAt = null; }
    public void finish(NodeStatus status, String outputsJson, String error, long durationMillis, Instant now) {
        this.status = status; this.outputsJson = outputsJson; this.error = error;
        this.durationMillis = durationMillis; this.finishedAt = now;
    }
}
