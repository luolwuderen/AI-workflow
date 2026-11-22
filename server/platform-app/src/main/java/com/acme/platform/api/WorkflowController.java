package com.acme.platform.api;

import com.acme.platform.service.AppService;
import com.acme.platform.service.RunService;
import com.acme.workflow.core.catalog.NodeCatalog;
import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.NodeCatalogEntry;
import com.acme.workflow.core.model.ValidationResult;
import com.acme.workflow.core.validation.GraphValidator;
import com.acme.workflow.infra.dify.DifyDslAdapter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class WorkflowController {
    private final NodeCatalog catalog;
    private final GraphValidator validator;
    private final AppService apps;
    private final RunService runs;
    private final DifyDslAdapter dify;

    public WorkflowController(NodeCatalog catalog, GraphValidator validator, AppService apps, RunService runs, DifyDslAdapter dify) {
        this.catalog = catalog; this.validator = validator; this.apps = apps; this.runs = runs; this.dify = dify;
    }

    @GetMapping("/catalog")
    public List<NodeCatalogEntry> catalog() { return catalog.all(); }

    @GetMapping("/apps")
    public List<AppView> apps() { return apps.list(); }

    @PostMapping("/apps")
    @ResponseStatus(HttpStatus.CREATED)
    public AppView create(@RequestBody CreateAppRequest request) { return apps.create(request.name(), request.appType()); }

    @GetMapping("/apps/{id}")
    public AppView app(@PathVariable String id) { return apps.get(id); }

    @DeleteMapping("/apps/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { apps.delete(id); }

    @PutMapping("/apps/{id}/draft")
    public AppView draft(@PathVariable String id, @RequestBody DraftRequest request) {
        return apps.updateDraft(id, request.graph(), request.expectedVersion());
    }

    @PostMapping("/apps/{id}/validate")
    public ValidationResult validate(@PathVariable String id, @RequestBody ValidateRequest request) {
        apps.get(id);
        return validator.validate(request.graph());
    }

    @PostMapping("/apps/{id}/runs")
    public ResponseEntity<RunView> run(@PathVariable String id, @RequestBody(required = false) RunRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(runs.start(id, request == null ? Map.of() : request.inputs()));
    }

    @GetMapping("/runs/{id}")
    public RunView run(@PathVariable String id) { return runs.get(id); }

    @PostMapping("/runs/{id}/nodes/{nodeId}/resume")
    public RunView resume(@PathVariable String id, @PathVariable String nodeId,
                          @RequestBody(required = false) ResumeRunRequest request) {
        return runs.resume(id, nodeId, request == null ? Map.of() : request.inputs());
    }

    @GetMapping("/runs/{id}/events")
    public List<EventView> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        return runs.events(id, after);
    }

    @GetMapping(path = "/runs/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        return runs.stream(id, after);
    }

    @PostMapping(path = "/dify/import", consumes = {MediaType.TEXT_PLAIN_VALUE, "application/yaml", "application/x-yaml"})
    public GraphDocument importDify(@RequestBody String yaml) { return dify.importYaml(yaml); }

    @GetMapping(path = "/apps/{id}/dify/export", produces = "application/yaml")
    public String exportDify(@PathVariable String id) {
        AppView app = apps.get(id);
        return dify.exportYaml(app.graph(), app.name());
    }

    public record CreateAppRequest(String name, AppType appType) { }
    public record DraftRequest(GraphDocument graph, long expectedVersion) { }
    public record ValidateRequest(GraphDocument graph) { }
    public record RunRequest(Map<String, Object> inputs) { }
    public record ResumeRunRequest(Map<String, Object> inputs) { }
}
