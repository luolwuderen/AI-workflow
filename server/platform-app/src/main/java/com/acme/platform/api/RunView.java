package com.acme.platform.api;

import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.runtime.RunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RunView(String id, String appId, RunStatus status, GraphDocument graph, Map<String, Object> inputs,
                      Map<String, Object> outputs, String error, Instant createdAt, Instant startedAt,
                      Instant finishedAt, List<NodeRunView> nodes) {
}
