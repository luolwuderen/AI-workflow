package com.acme.workflow.runtime;

public class NodeExecutionException extends RuntimeException {
    private final String nodeId;

    public NodeExecutionException(String nodeId, String message) {
        super(message);
        this.nodeId = nodeId;
    }

    public NodeExecutionException(String nodeId, String message, Throwable cause) {
        super(message, cause);
        this.nodeId = nodeId;
    }

    public String nodeId() { return nodeId; }
}
