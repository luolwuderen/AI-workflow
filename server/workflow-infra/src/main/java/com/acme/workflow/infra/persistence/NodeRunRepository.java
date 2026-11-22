package com.acme.workflow.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NodeRunRepository extends JpaRepository<NodeRunEntity, String> {
    List<NodeRunEntity> findByRunIdOrderByStartedAtAsc(String runId);
    Optional<NodeRunEntity> findByRunIdAndNodeId(String runId, String nodeId);
    long deleteByRunIdIn(Collection<String> runIds);
}
