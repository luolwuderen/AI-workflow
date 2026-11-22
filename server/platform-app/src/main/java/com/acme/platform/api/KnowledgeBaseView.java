package com.acme.platform.api;

import java.time.Instant;

public record KnowledgeBaseView(String id, String name, String description, Instant createdAt, Instant updatedAt) { }
