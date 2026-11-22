package com.acme.platform.api;

import com.acme.workflow.runtime.NodeStatus;

import java.time.Instant;
import java.util.Map;

public record NodeRunView(String nodeId, String nodeType, NodeStatus status, Map<String, Object> inputs,
                          Map<String, Object> outputs, String error, long durationMillis,
                          Instant startedAt, Instant finishedAt) {
}
