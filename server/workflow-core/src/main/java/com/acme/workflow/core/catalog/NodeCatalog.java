package com.acme.workflow.core.catalog;

import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.NodeCatalogEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class NodeCatalog {
    public static final String UNSUPPORTED = "当前版本仅支持配置与保存，运行时尚未支持该节点。";
    private static final List<AppType> BOTH = List.of(AppType.WORKFLOW, AppType.CHATFLOW);
    private static final List<AppType> WORKFLOW = List.of(AppType.WORKFLOW);
    private static final List<AppType> CHATFLOW = List.of(AppType.CHATFLOW);
    private final Map<String, NodeCatalogEntry> entries;

    public NodeCatalog() {
        Map<String, NodeCatalogEntry> values = new LinkedHashMap<>();
        register(values, executable("user-input", "User Input", "定义并校验运行输入。", "开始", BOTH,
                userInputSchema(), obj("fields", List.of()), outputs()));
        register(values, unsupported("schedule-trigger", "Schedule Trigger", "按计划触发工作流。", "开始", WORKFLOW,
                schema(obj("cron", obj("type", "string", "title", "Cron 表达式")), List.of("cron"))));
        register(values, unsupported("plugin-trigger", "Plugin Trigger", "由 Dify 插件事件触发。", "开始", WORKFLOW,
                schema(obj("pluginId", obj("type", "string"), "event", obj("type", "string")), List.of("pluginId", "event"))));
        register(values, unsupported("webhook-trigger", "Webhook Trigger", "由 HTTP Webhook 触发。", "开始", WORKFLOW,
                schema(obj("method", obj("type", "string", "enum", List.of("POST", "PUT", "PATCH")), "path", obj("type", "string")), List.of("path"))));
        register(values, executable("llm", "LLM", "通过 Spring AI 调用 OpenAI 兼容模型。", "AI", BOTH,
                llmSchema(), obj("model", "", "messages", List.of(obj("role", "user", "content", "")), "temperature", 0.7,
                        "topP", 1.0, "maxTokens", 2048, "stream", true, "responseFormat", "text"),
                outputs(obj("text", obj("type", "string")), obj("usage", obj("type", "object")))));
        register(values, unsupported("knowledge-retrieval", "Knowledge Retrieval", "从知识库检索上下文。", "AI", BOTH,
                schema(obj("datasetIds", obj("type", "array", "items", obj("type", "string")), "query", obj("type", "string"),
                        "topK", obj("type", "integer", "minimum", 1)), List.of("datasetIds", "query"))));
        register(values, executable("answer", "Answer", "替换变量并流式返回聊天答案。", "输出", CHATFLOW,
                schema(obj("answer", obj("type", "string", "title", "答案模板")), List.of("answer")),
                obj("answer", ""), outputs(obj("answer", obj("type", "string")))));
        register(values, executable("output", "Output", "声明工作流输出映射，可配置多个 Output。", "输出", WORKFLOW,
                outputSchema(), obj("mappings", List.of()), outputs()));
        register(values, executable("agent", "Agent", "使用 function_call 策略循环调用内置工具，给出最终答案。", "AI", BOTH,
                agentSchema(), obj("model", "", "instruction", "请回答：{{start.text}}",
                        "strategy", "function_call", "tools", List.of("calculator"), "maxIterations", 5,
                        "temperature", 0.7, "topP", 1.0, "maxTokens", 2048),
                outputs(obj("text", obj("type", "string")), obj("iterations", obj("type", "number")),
                        obj("usage", obj("type", "object")))));
        register(values, unsupported("question-classifier", "Question Classifier", "使用模型对问题分类。", "AI", BOTH,
                schema(obj("model", obj("type", "string"), "query", obj("type", "string"), "classes",
                        obj("type", "array", "items", obj("type", "object", "properties", obj("id", obj("type", "string"), "name", obj("type", "string"))))),
                        List.of("model", "query", "classes"))));
        register(values, executable("if-else", "IF / ELIF / ELSE", "按条件选择一个确定分支。", "控制", BOTH,
                ifSchema(), obj("cases", List.of(obj("id", "case-1", "logicalOperator", "and", "conditions", List.of()))),
                outputs(obj("branch", obj("type", "string")))));
        register(values, executable("human-input", "Human Input", "暂停当前运行，提交表单后从原位置继续。", "控制", BOTH,
                humanInputSchema(), obj("prompt", "请提供人工输入",
                        "fields", List.of(obj("variable", "note", "label", "处理意见", "type", "text", "required", true))),
                outputs()));
        register(values, executable("iteration", "Iteration", "对列表逐项执行作用域子图，迭代体内可用 {{item}} 与 {{index}}。", "控制", BOTH,
                iterationSchema(), obj("inputSelector", "", "outputSelector", "", "parallel", false, "maxParallelism", 4),
                outputs(obj("output", obj("type", "array")), obj("count", obj("type", "number"))),
                obj("inputSelector", obj("ui:widget", "variable"), "outputSelector", obj("ui:widget", "variable"))));
        register(values, executable("loop", "Loop", "循环执行作用域子图，达到最大次数或中断条件时退出。", "控制", BOTH,
                loopSchema(), obj("maxIterations", 10, "outputSelector", "", "breakConditions", List.of(), "breakLogicalOperator", "and"),
                outputs(obj("output", obj("type", "any")), obj("iterations", obj("type", "number"))),
                obj("outputSelector", obj("ui:widget", "variable"),
                        "breakConditions", obj("variable", obj("ui:widget", "variable")))));
        register(values, executable("exit-loop", "Exit Loop", "满足条件时终止当前 Loop 的后续迭代。", "控制", BOTH,
                exitLoopSchema(), obj("conditions", List.of(), "logicalOperator", "and"),
                outputs(obj("break", obj("type", "boolean"))),
                obj("conditions", obj("variable", obj("ui:widget", "variable")))));
        register(values, executable("code", "Code", "仅通过官方 dify-sandbox HTTP API 执行代码。", "数据", BOTH,
                codeSchema(), obj("language", "python3", "code", "", "inputs", List.of(), "outputs", obj()),
                outputs()));
        register(values, executable("template", "Template", "使用 {{变量}} 替换渲染文本模板。", "数据", BOTH,
                templateSchema(), obj("template", "", "variables", List.of()),
                outputs(obj("output", obj("type", "string")))));
        register(values, executable("variable-aggregator", "Variable Aggregator", "按顺序取第一个非空变量。", "控制", BOTH,
                aggregatorSchema(), obj("variables", List.of()),
                outputs(obj("output", obj("type", "any")))));
        register(values, executable("variable-assigner", "Variable Assigner", "写入运行期变量，供下游节点引用。", "数据", BOTH,
                assignerSchema(), obj("variable", "", "value", ""),
                outputs(obj("output", obj("type", "any")))));
        register(values, unsupported("document-extractor", "Document Extractor", "从文档提取文本。", "数据", BOTH,
                schema(obj("inputSelector", obj("type", "string")), List.of("inputSelector"))));
        register(values, unsupported("parameter-extractor", "Parameter Extractor", "使用模型抽取结构化参数。", "AI", BOTH,
                schema(obj("model", obj("type", "string"), "input", obj("type", "string"), "parameters", obj("type", "array", "items", obj("type", "object"))), List.of("model", "input", "parameters"))));
        register(values, executable("http-request", "HTTP Request", "发送受控 HTTP 请求，默认阻断内网目标。", "数据", BOTH,
                httpSchema(), obj("method", "GET", "url", "", "headers", obj(), "bodyType", "none", "body", "", "timeoutMillis", 10000),
                outputs(obj("status", obj("type", "number")), obj("headers", obj("type", "object")),
                        obj("body", obj("type", "any")), obj("url", obj("type", "string")))));
        register(values, executable("list-operator", "List Operator", "在内存中过滤、排序、截取列表。", "数据", BOTH,
                listOperatorSchema(), obj("inputSelector", "", "filterOperator", "none", "filterKey", "", "filterValue", "",
                        "sortEnabled", false, "sortKey", "", "sortOrder", "asc", "extractSerial", 0, "limit", 0),
                outputs(obj("output", obj("type", "any")))));
        register(values, unsupported("tool", "Tool", "调用 Dify 兼容工具。", "扩展", BOTH,
                schema(obj("providerId", obj("type", "string"), "toolName", obj("type", "string"), "parameters", obj("type", "object")), List.of("providerId", "toolName"))));
        entries = Map.copyOf(values);
    }

    public List<NodeCatalogEntry> all() {
        return List.copyOf(entries.values());
    }

    public Optional<NodeCatalogEntry> find(String type) {
        return Optional.ofNullable(entries.get(type));
    }

    public NodeCatalogEntry get(String type) {
        NodeCatalogEntry entry = entries.get(type);
        if (entry == null) throw new IllegalArgumentException("Unknown node type: " + type);
        return entry;
    }

    private static void register(Map<String, NodeCatalogEntry> values, NodeCatalogEntry entry) {
        values.put(entry.type(), entry);
    }

    private static NodeCatalogEntry executable(String type, String title, String description, String category,
                                                List<AppType> apps, Map<String, Object> schema,
                                                Map<String, Object> defaults, Map<String, Object> outputs) {
        return new NodeCatalogEntry(type, title, description, category, apps, true, "当前版本支持真实执行。",
                schema, obj("ui:order", new ArrayList<>(properties(schema).keySet())), defaults, outputs);
    }

    private static NodeCatalogEntry executable(String type, String title, String description, String category,
                                                List<AppType> apps, Map<String, Object> schema,
                                                Map<String, Object> defaults, Map<String, Object> outputs,
                                                Map<String, Object> uiOverrides) {
        Map<String, Object> ui = obj("ui:order", new ArrayList<>(properties(schema).keySet()));
        ui.putAll(uiOverrides);
        return new NodeCatalogEntry(type, title, description, category, apps, true, "当前版本支持真实执行。",
                schema, ui, defaults, outputs);
    }

    private static NodeCatalogEntry unsupported(String type, String title, String description, String category,
                                                 List<AppType> apps, Map<String, Object> schema) {
        return new NodeCatalogEntry(type, title, description, category, apps, false, UNSUPPORTED,
                schema, obj("ui:order", new ArrayList<>(properties(schema).keySet())), obj(), outputs());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.getOrDefault("properties", Map.of());
    }

    private static Map<String, Object> userInputSchema() {
        return schema(obj("fields", obj("type", "array", "minItems", 1, "items", inputFieldSchema())), List.of("fields"));
    }

    private static Map<String, Object> humanInputSchema() {
        return schema(obj("prompt", obj("type", "string", "minLength", 1, "title", "人工处理说明"),
                        "fields", obj("type", "array", "minItems", 1, "items", inputFieldSchema())),
                List.of("prompt", "fields"));
    }

    private static Map<String, Object> inputFieldSchema() {
        return obj("type", "object", "additionalProperties", false,
                "required", List.of("variable", "label", "type"),
                "properties", obj("variable", obj("type", "string", "minLength", 1), "label", obj("type", "string"),
                        "type", obj("type", "string", "enum", List.of("text", "paragraph", "number", "select", "boolean", "json")),
                        "required", obj("type", "boolean", "default", false), "default", obj(),
                        "options", obj("type", "array", "items", obj("type", "string"))));
    }

    private static Map<String, Object> llmSchema() {
        return schema(obj("model", obj("type", "string", "minLength", 1),
                        "messages", obj("type", "array", "minItems", 1, "items", obj("type", "object", "additionalProperties", false,
                                "required", List.of("role", "content"), "properties", obj("role", obj("type", "string", "enum", List.of("system", "user", "assistant")),
                                        "content", obj("type", "string")))),
                        "temperature", obj("type", "number", "minimum", 0, "maximum", 2),
                        "topP", obj("type", "number", "minimum", 0, "maximum", 1),
                        "maxTokens", obj("type", "integer", "minimum", 1),
                        "stop", obj("type", "array", "items", obj("type", "string")),
                        "responseFormat", obj("type", "string", "enum", List.of("text", "json_object", "json_schema")),
                        "jsonSchema", obj("type", "object"), "stream", obj("type", "boolean", "default", true)),
                List.of("model", "messages"));
    }

    private static Map<String, Object> agentSchema() {
        // tools 的 enum 与首版内置工具一致；新增内置工具时需同步扩展此列表。
        return schema(obj(
                        "model", obj("type", "string", "minLength", 1, "title", "模型"),
                        "instruction", obj("type", "string", "minLength", 1, "title", "提示词（支持 {{节点.变量}}）"),
                        "strategy", obj("type", "string", "enum", List.of("function_call"), "default", "function_call", "title", "策略"),
                        "tools", obj("type", "array", "items", obj("type", "string", "enum", List.of("calculator")),
                                "default", List.of("calculator"), "title", "启用工具"),
                        "maxIterations", obj("type", "integer", "minimum", 1, "maximum", 20, "default", 5, "title", "最大循环次数"),
                        "temperature", obj("type", "number", "minimum", 0, "maximum", 2, "default", 0.7),
                        "topP", obj("type", "number", "minimum", 0, "maximum", 1, "default", 1.0),
                        "maxTokens", obj("type", "integer", "minimum", 1, "default", 2048)),
                List.of("model", "instruction"));
    }

    private static Map<String, Object> ifSchema() {
        Map<String, Object> condition = obj("type", "object", "additionalProperties", false,
                "required", List.of("variable", "operator"), "properties", obj("variable", obj("type", "string"),
                        "operator", obj("type", "string", "enum", List.of("equals", "not-equals", "contains", "not-contains", "starts-with", "ends-with",
                                "is-empty", "not-empty", "greater", "greater-or-equal", "less", "less-or-equal"))), "value", obj());
        Map<String, Object> oneCase = obj("type", "object", "additionalProperties", false,
                "required", List.of("id", "conditions"), "properties", obj("id", obj("type", "string"),
                        "logicalOperator", obj("type", "string", "enum", List.of("and", "or"), "default", "and"),
                        "conditions", obj("type", "array", "minItems", 1, "items", condition)));
        return schema(obj("cases", obj("type", "array", "minItems", 1, "items", oneCase)), List.of("cases"));
    }

    private static Map<String, Object> iterationSchema() {
        return schema(obj(
                        "inputSelector", obj("type", "string", "minLength", 1, "title", "迭代输入（列表）"),
                        "outputSelector", obj("type", "string", "title", "每项输出（留空取末节点 output）"),
                        "parallel", obj("type", "boolean", "default", false, "title", "并行执行"),
                        "maxParallelism", obj("type", "integer", "minimum", 1, "maximum", 32, "default", 4, "title", "最大并发")),
                List.of("inputSelector"));
    }

    private static Map<String, Object> loopSchema() {
        return schema(obj(
                        "maxIterations", obj("type", "integer", "minimum", 1, "maximum", 100, "default", 10, "title", "最大迭代次数"),
                        "outputSelector", obj("type", "string", "title", "循环输出（留空取最后执行节点）"),
                        "breakConditions", conditionArraySchema("中断条件"),
                        "breakLogicalOperator", obj("type", "string", "enum", List.of("and", "or"), "default", "and",
                                "title", "中断条件组合")),
                List.of("maxIterations"));
    }

    private static Map<String, Object> exitLoopSchema() {
        return schema(obj(
                        "conditions", conditionArraySchema("退出条件"),
                        "logicalOperator", obj("type", "string", "enum", List.of("and", "or"), "default", "and",
                                "title", "条件组合")),
                List.of());
    }

    private static Map<String, Object> conditionArraySchema(String title) {
        Map<String, Object> condition = obj("type", "object", "additionalProperties", false,
                "required", List.of("variable", "operator"),
                "properties", obj("variable", obj("type", "string", "title", "变量"),
                        "operator", obj("type", "string", "title", "运算符", "enum", List.of("equals", "not-equals",
                                "contains", "not-contains", "starts-with", "ends-with", "is-empty", "not-empty",
                                "greater", "greater-or-equal", "less", "less-or-equal")),
                        "value", obj("title", "比较值")));
        return obj("type", "array", "title", title, "items", condition);
    }

    private static Map<String, Object> codeSchema() {
        Map<String, Object> input = obj("type", "object", "additionalProperties", false, "required", List.of("name", "selector"),
                "properties", obj("name", obj("type", "string"), "selector", obj("type", "string")));
        return schema(obj("language", obj("type", "string", "enum", List.of("python3", "javascript")),
                        "code", obj("type", "string", "minLength", 1), "inputs", obj("type", "array", "items", input),
                        "outputs", obj("type", "object", "additionalProperties", obj("type", "object", "required", List.of("type"),
                                "properties", obj("type", obj("type", "string", "enum", List.of("string", "number", "boolean", "object", "array")))))),
                List.of("language", "code", "outputs"));
    }

    private static Map<String, Object> templateSchema() {
        Map<String, Object> variable = obj("type", "object", "additionalProperties", false,
                "required", List.of("variable", "selector"),
                "properties", obj("variable", obj("type", "string", "minLength", 1), "selector", obj("type", "string", "minLength", 1)));
        return schema(obj("template", obj("type", "string", "minLength", 1, "title", "模板"),
                        "variables", obj("type", "array", "items", variable)),
                List.of("template"));
    }

    private static Map<String, Object> aggregatorSchema() {
        Map<String, Object> variable = obj("type", "object", "additionalProperties", false,
                "required", List.of("selector"),
                "properties", obj("selector", obj("type", "string", "minLength", 1)));
        return schema(obj("variables", obj("type", "array", "minItems", 1, "items", variable)), List.of("variables"));
    }

    private static Map<String, Object> assignerSchema() {
        return schema(obj("variable", obj("type", "string", "minLength", 1, "title", "变量名"),
                        "value", obj("type", "string", "title", "值（支持 {{节点.变量}} 引用）")),
                List.of("variable", "value"));
    }

    private static Map<String, Object> listOperatorSchema() {
        return schema(obj(
                        "inputSelector", obj("type", "string", "minLength", 1, "title", "输入列表"),
                        "filterOperator", obj("type", "string", "title", "过滤操作",
                                "enum", List.of("none", "equals", "not-equals", "contains", "greater", "less"), "default", "none"),
                        "filterKey", obj("type", "string", "title", "过滤字段（留空表示比较元素本身）"),
                        "filterValue", obj("type", "string", "title", "过滤值"),
                        "sortEnabled", obj("type", "boolean", "title", "启用排序", "default", false),
                        "sortKey", obj("type", "string", "title", "排序字段（留空表示排序元素本身）"),
                        "sortOrder", obj("type", "string", "enum", List.of("asc", "desc"), "default", "asc", "title", "排序方向"),
                        "extractSerial", obj("type", "integer", "minimum", 0, "default", 0, "title", "提取第 N 项（从 1 开始，0 表示不提取）"),
                        "limit", obj("type", "integer", "minimum", 0, "default", 0, "title", "最多保留条数（0 表示不限制）")),
                List.of("inputSelector"));
    }

    private static Map<String, Object> httpSchema() {
        return schema(obj(
                        "method", obj("type", "string", "title", "方法",
                                "enum", List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"), "default", "GET"),
                        "url", obj("type", "string", "minLength", 1, "title", "URL"),
                        "headers", obj("type", "object", "title", "请求头（JSON 对象）"),
                        "bodyType", obj("type", "string", "title", "请求体类型",
                                "enum", List.of("none", "json", "form", "raw"), "default", "none"),
                        "body", obj("type", "string", "title", "请求体"),
                        "timeoutMillis", obj("type", "integer", "minimum", 1000, "maximum", 60000,
                                "default", 10000, "title", "超时（毫秒）")),
                List.of("method", "url"));
    }

    private static Map<String, Object> outputSchema() {
        Map<String, Object> mapping = obj("type", "object", "additionalProperties", false, "required", List.of("name", "selector"),
                "properties", obj("name", obj("type", "string", "minLength", 1), "selector", obj("type", "string", "minLength", 1)));
        return schema(obj("mappings", obj("type", "array", "minItems", 1, "items", mapping)), List.of("mappings"));
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return obj("type", "object", "additionalProperties", false, "properties", properties, "required", required);
    }

    private static Map<String, Object> outputs(Object... values) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> outputs) outputs.forEach((key, schema) -> properties.put(String.valueOf(key), schema));
        }
        return obj("type", "object", "properties", properties);
    }

    static Map<String, Object> obj(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
