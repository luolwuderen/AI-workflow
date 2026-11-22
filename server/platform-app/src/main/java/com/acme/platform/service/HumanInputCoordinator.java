package com.acme.platform.service;

import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.runtime.HumanInputGateway;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

@Component
public class HumanInputCoordinator implements HumanInputGateway {
    private final Map<WaitKey, CompletableFuture<Map<String, Object>>> waiting = new ConcurrentHashMap<>();

    @Override
    public void prepare(String runId, GraphNode node) {
        WaitKey key = new WaitKey(runId, node.id());
        if (waiting.putIfAbsent(key, new CompletableFuture<>()) != null)
            throw new IllegalStateException("该节点已在等待人工输入。");
    }

    @Override
    public Map<String, Object> awaitInput(String runId, GraphNode node) throws InterruptedException {
        WaitKey key = new WaitKey(runId, node.id());
        CompletableFuture<Map<String, Object>> future = waiting.computeIfAbsent(key, ignored -> new CompletableFuture<>());
        try {
            return future.get();
        } catch (ExecutionException error) {
            throw new IllegalStateException("人工输入等待失败。", error.getCause());
        } finally {
            waiting.remove(key, future);
        }
    }

    public boolean submit(String runId, String nodeId, Map<String, Object> inputs) {
        CompletableFuture<Map<String, Object>> future = waiting.get(new WaitKey(runId, nodeId));
        return future != null && future.complete(new LinkedHashMap<>(inputs));
    }

    private record WaitKey(String runId, String nodeId) { }
}
