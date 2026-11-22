package com.acme.workflow.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RunEventRepository extends JpaRepository<RunEventEntity, Long> {
    List<RunEventEntity> findByRunIdAndIdGreaterThanOrderByIdAsc(String runId, long id);
    List<RunEventEntity> findByRunIdOrderByIdAsc(String runId);
    long deleteByRunIdIn(Collection<String> runIds);
}
