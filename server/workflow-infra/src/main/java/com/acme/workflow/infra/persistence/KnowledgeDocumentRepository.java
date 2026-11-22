package com.acme.workflow.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, String> {
    List<KnowledgeDocumentEntity> findByKnowledgeBaseIdOrderByCreatedAtAsc(String knowledgeBaseId);
}
