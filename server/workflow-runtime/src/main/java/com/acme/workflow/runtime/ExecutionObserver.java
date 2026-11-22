package com.acme.workflow.runtime;

import com.acme.workflow.core.model.GraphNode;

import java.util.Map;

public interface ExecutionObserver {
    ExecutionObserver NOOP = new ExecutionObserver() { };

    default void emit(String runId, String type, String nodeId, Map<String, Object> data) { }
    default void nodeStarted(String runId, GraphNode node, Map<String, Object> input) { }
    default void nodeFinished(String runId, GraphNode node, NodeStatus status, Map<String, Object> output,
                              String error, long durationMillis) { }
    default void nodeWaiting(String runId, GraphNode node, Map<String, Object> request) { }
    default void nodeResumed(String runId, GraphNode node) { }
    default void runPaused(String runId, GraphNode node) { }
    default void runResumed(String runId, GraphNode node, int remainingWaiters) { }
    default void runFinished(String runId, RunResult result) { }
}
