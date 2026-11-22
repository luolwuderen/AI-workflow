package com.acme.platform.service;

import com.acme.platform.api.AppView;
import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphExamples;
import com.acme.workflow.infra.persistence.AppEntity;
import com.acme.workflow.infra.persistence.AppRepository;
import com.acme.workflow.infra.persistence.JsonCodec;
import com.acme.workflow.infra.persistence.NodeRunRepository;
import com.acme.workflow.infra.persistence.RunEntity;
import com.acme.workflow.infra.persistence.RunEventRepository;
import com.acme.workflow.infra.persistence.RunRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AppService {
    private final AppRepository apps;
    private final JsonCodec json;
    private final RunRepository runs;
    private final NodeRunRepository nodeRuns;
    private final RunEventRepository runEvents;

    public AppService(AppRepository apps, JsonCodec json, RunRepository runs, NodeRunRepository nodeRuns, RunEventRepository runEvents) {
        this.apps = apps; this.json = json; this.runs = runs; this.nodeRuns = nodeRuns; this.runEvents = runEvents;
    }

    public List<AppView> list() {
        return apps.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    public AppView create(String name, AppType appType) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空。");
        if (appType == null) throw new IllegalArgumentException("appType 必须为 WORKFLOW 或 CHATFLOW。");
        GraphDocument graph = GraphExamples.create(appType);
        AppEntity app = new AppEntity(UUID.randomUUID().toString(), name.trim(), appType, json.write(graph), Instant.now());
        return view(apps.saveAndFlush(app));
    }

    public AppView get(String id) { return view(entity(id)); }

    @Transactional
    public void delete(String id) {
        AppEntity app = entity(id);
        List<RunEntity> appRuns = runs.findByAppId(id);
        List<String> runIds = appRuns.stream().map(RunEntity::getId).toList();
        if (!runIds.isEmpty()) {
            runEvents.deleteByRunIdIn(runIds);
            nodeRuns.deleteByRunIdIn(runIds);
            nodeRuns.flush();
            runs.deleteAllInBatch(appRuns);
        }
        apps.delete(app);
    }

    @Transactional
    public AppView updateDraft(String id, GraphDocument graph, long expectedVersion) {
        if (graph == null || graph.version() != 1) throw new IllegalArgumentException("graph.version 必须为 1。");
        AppEntity app = entity(id);
        if (app.getVersion() != expectedVersion) throw new VersionConflictException(expectedVersion, app.getVersion());
        if (graph.appType() != app.getAppType()) throw new IllegalArgumentException("graph.appType 不能修改应用类型。");
        try {
            app.updateDraft(json.write(graph), Instant.now());
            return view(apps.saveAndFlush(app));
        } catch (OptimisticLockingFailureException error) {
            throw new VersionConflictException(expectedVersion, entity(id).getVersion());
        }
    }

    public AppEntity entity(String id) {
        return apps.findById(id).orElseThrow(() -> new NotFoundException("应用不存在：" + id));
    }

    private AppView view(AppEntity app) {
        return new AppView(app.getId(), app.getName(), app.getAppType(), json.graph(app.getDraftJson()), app.getVersion(),
                app.getCreatedAt(), app.getUpdatedAt());
    }
}
