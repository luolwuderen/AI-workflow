package com.acme.workflow.infra.execution;

import com.acme.workflow.infra.knowledge.KnowledgeQueryService;
import com.acme.workflow.runtime.NodeExecutionContext;
import com.acme.workflow.runtime.NodeExecutionException;
import com.acme.workflow.runtime.NodeExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 知识检索：按 datasetIds 在平台知识库中做向量检索，未命中时返回空结果而非报错。 */
@Component
public class KnowledgeRetrievalExecutor implements NodeExecutor {
    private final KnowledgeQueryService queryService;

    public KnowledgeRetrievalExecutor(KnowledgeQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String type() { return "knowledge-retrieval"; }

    @Override
    public Map<String, Object> execute(NodeExecutionContext context) {
        try {
            Map<String, Object> config = context.node().config();
            List<String> knowledgeBaseIds = strings(config.get("datasetIds"));
            if (knowledgeBaseIds.isEmpty())
                throw new IllegalArgumentException("datasetIds 至少需要一个知识库。");
            String query = context.render(String.valueOf(config.getOrDefault("query", "")));
            if (query.isBlank()) throw new IllegalArgumentException("query 必填。");
            int topK = config.get("topK") instanceof Number value ? value.intValue() : 4;
            double threshold = config.get("scoreThreshold") instanceof Number value ? value.doubleValue() : 0.0;

            List<KnowledgeQueryService.Hit> hits = queryService.search(knowledgeBaseIds, query, topK, threshold);
            List<Map<String, Object>> result = new ArrayList<>(hits.size());
            for (KnowledgeQueryService.Hit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("content", hit.content());
                item.put("score", hit.score());
                item.put("documentName", hit.documentName());
                item.put("knowledgeBaseId", hit.knowledgeBaseId());
                item.put("documentId", hit.documentId());
                item.put("chunkIndex", hit.chunkIndex());
                result.add(item);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("result", result);
            output.put("context", hits.stream().map(KnowledgeQueryService.Hit::content)
                    .collect(Collectors.joining("\n\n")));
            output.put("count", result.size());
            return output;
        } catch (NodeExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new NodeExecutionException(context.node().id(), "知识检索失败：" + error.getMessage(), error);
        }
    }

    private List<String> strings(Object raw) {
        List<String> values = new ArrayList<>();
        if (!(raw instanceof List<?> items)) return values;
        for (Object item : items) {
            if (!(item instanceof String text) || text.isBlank()) continue;
            values.add(text);
        }
        return values;
    }
}
