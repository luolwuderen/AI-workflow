package com.acme.workflow.runtime.agent;

import java.util.Map;

/**
 * Agent 节点可调用的内置工具 SPI。
 * 首版仅提供 {@code calculator}，后续 code / http / 插件工具通过新增实现接入。
 */
public interface AgentTool {

    /** 工具名，与节点 config.tools 中配置的名称一一对应。 */
    String name();

    /** 给模型看的工具描述。 */
    String description();

    /** 工具输入参数的 JSON Schema（对象形式），会被序列化后发送给模型。 */
    Map<String, Object> inputSchema();

    /**
     * 执行工具。
     *
     * @param argumentsJson 模型给出的原始 JSON 参数字符串
     * @return 工具结果的 JSON 字符串
     */
    String call(String argumentsJson);
}
