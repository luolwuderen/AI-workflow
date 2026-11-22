package com.acme.workflow.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppRepository extends JpaRepository<AppEntity, String> {
    List<AppEntity> findAllByOrderByCreatedAtDesc();
}
