package com.acme.platform.api;

import java.time.Instant;

public record KnowledgeDocumentView(String id, String knowledgeBaseId, String name, int chunkCount, Instant createdAt) { }
