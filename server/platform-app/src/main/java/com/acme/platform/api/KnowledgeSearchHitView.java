package com.acme.platform.api;

public record KnowledgeSearchHitView(String knowledgeBaseId, String documentId, String documentName,
                                     String content, int chunkIndex, double score) { }
