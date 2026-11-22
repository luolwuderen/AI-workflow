package com.acme.workflow.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {
    List<KnowledgeBaseEntity> findAllByOrderByCreatedAtDesc();
}
