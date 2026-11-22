package com.acme.workflow.runtime;

import com.acme.workflow.core.model.GraphNode;

import java.util.Map;

/** 由运行环境提供人工输入等待与提交能力。 */
public interface HumanInputGateway {
    HumanInputGateway UNSUPPORTED = (runId, node) -> {
        throw new NodeExecutionException(node.id(), "当前运行环境未配置 Human Input 恢复服务。");
    };

    default void prepare(String runId, GraphNode node) { }

    Map<String, Object> awaitInput(String runId, GraphNode node) throws InterruptedException;
}
