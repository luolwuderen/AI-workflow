package com.acme.workflow.infra.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, Long> {
    List<KnowledgeChunkEntity> findByKnowledgeBaseIdIn(Collection<String> knowledgeBaseIds, Pageable pageable);

    void deleteByDocumentIdIn(Collection<String> documentIds);

    void deleteByKnowledgeBaseId(String knowledgeBaseId);
}
