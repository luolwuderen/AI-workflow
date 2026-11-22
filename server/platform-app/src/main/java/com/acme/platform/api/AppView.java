package com.acme.platform.api;

import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;

import java.time.Instant;

public record AppView(String id, String name, AppType appType, GraphDocument graph, long version,
                      Instant createdAt, Instant updatedAt) {
}
