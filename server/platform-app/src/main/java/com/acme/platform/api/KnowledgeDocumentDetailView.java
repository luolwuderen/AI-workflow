package com.acme.platform.api;

import java.time.Instant;

public record KnowledgeDocumentDetailView(String id, String knowledgeBaseId, String name, String content,
                                          int chunkCount, Instant createdAt) { }
