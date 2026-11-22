package com.acme.platform.api;

import com.acme.platform.service.KnowledgeBaseService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService knowledgeBases;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
    }

    @GetMapping
    public List<KnowledgeBaseView> list() { return knowledgeBases.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBaseView create(@RequestBody CreateKnowledgeBaseRequest request) {
        return knowledgeBases.create(request.name(), request.description());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { knowledgeBases.delete(id); }

    @GetMapping("/{id}/documents")
    public List<KnowledgeDocumentView> documents(@PathVariable String id) { return knowledgeBases.documents(id); }

    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDocumentView addDocument(@PathVariable String id, @RequestBody AddDocumentRequest request) {
        return knowledgeBases.addDocument(id, request.name(), request.content());
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable String id, @PathVariable String documentId) {
        knowledgeBases.deleteDocument(id, documentId);
    }

    public record CreateKnowledgeBaseRequest(String name, String description) { }
    public record AddDocumentRequest(String name, String content) { }
}
