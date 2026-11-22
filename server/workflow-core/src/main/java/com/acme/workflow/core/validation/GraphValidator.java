package com.acme.workflow.core.validation;

import com.acme.workflow.core.catalog.NodeCatalog;
import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphEdge;
import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.NodeCatalogEntry;
import com.acme.workflow.core.model.ValidationIssue;
import com.acme.workflow.core.model.ValidationResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GraphValidator {
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([\\w-]+)\\.([\\w.-]+)\\s*}}");
    private static final Set<String> STARTS = Set.of("user-input", "schedule-trigger", "plugin-trigger", "webhook-trigger");
    private static final Set<String> SCOPES = Set.of("iteration", "loop");
    private static final Set<String> SCOPE_FORBIDDEN = Set.of("user-input", "schedule-trigger", "plugin-trigger",
            "webhook-trigger", "output", "answer", "iteration", "loop");
    private static final Set<String> CONDITION_OPERATORS = Set.of("equals", "not-equals", "contains", "not-contains",
            "starts-with", "ends-with", "is-empty", "not-empty", "greater", "greater-or-equal", "less", "less-or-equal");
    private final NodeCatalog catalog;

    public GraphValidator(NodeCatalog catalog) {
        this.catalog = catalog;
    }

    public ValidationResult validate(GraphDocument graph) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (graph == null) {
            issues.add(new ValidationIssue("$", "GRAPH_REQUIRED", "图不能为空。"));
            return new ValidationResult(issues);
        }
        if (graph.version() != 1) issue(issues, "$.version", "VERSION", "仅支持 GraphDocument version=1。");
        if (graph.appType() == null) issue(issues, "$.appType", "APP_TYPE", "appType 必须为 WORKFLOW 或 CHATFLOW。");
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Set<String> edgeIds = new HashSet<>();
        Map<String, List<GraphEdge>> outgoing = new HashMap<>();
        Map<String, List<GraphEdge>> incoming = new HashMap<>();
        int startCount = 0;
        Set<String> outputNames = new HashSet<>();
        for (int i = 0; i < graph.nodes().size(); i++) {
            GraphNode node = graph.nodes().get(i);
            String path = "$.nodes[" + i + "]";
            if (node == null || blank(node.id()) || blank(node.type())) {
                issue(issues, path, "NODE_IDENTITY", "节点 id 与 type 必填。");
                continue;
            }
            if (nodes.putIfAbsent(node.id(), node) != null) issue(issues, path + ".id", "DUPLICATE_ID", "节点 id 重复。");
            if (node.position() == null || !Double.isFinite(node.position().x()) || !Double.isFinite(node.position().y()))
                issue(issues, path + ".position", "POSITION", "节点 position.x/y 必须为有限数值。");
            NodeCatalogEntry entry = catalog.find(node.type()).orElse(null);
            if (entry == null) {
                issue(issues, path + ".type", "UNKNOWN_NODE", "未知节点类型 " + node.type() + "。");
                continue;
            }
            if (graph.appType() != null && !entry.appTypes().contains(graph.appType()))
                issue(issues, path + ".type", "APP_TYPE", entry.title() + " 不适用于 " + graph.appType() + "。");
            if (!entry.executable()) issue(issues, path + ".type", "UNSUPPORTED_NODE", entry.supportMessage());
            if (STARTS.contains(node.type())) startCount++;
            validateConfig(node, path + ".config", issues, outputNames);
            incoming.computeIfAbsent(node.id(), ignored -> new ArrayList<>());
        }
        if (startCount != 1) issue(issues, "$.nodes", "START_COUNT", "图必须且只能包含一个开始节点。");
        for (int i = 0; i < graph.edges().size(); i++) {
            GraphEdge edge = graph.edges().get(i);
            String path = "$.edges[" + i + "]";
            if (edge == null || blank(edge.id()) || blank(edge.source()) || blank(edge.target())) {
                issue(issues, path, "EDGE_IDENTITY", "边 id、source、target 必填。");
                continue;
            }
            if (!edgeIds.add(edge.id())) issue(issues, path + ".id", "DUPLICATE_ID", "边 id 重复。");
            GraphNode source = nodes.get(edge.source());
            if (source == null || !nodes.containsKey(edge.target())) {
                issue(issues, path, "EDGE_ENDPOINT", "边必须连接图中已存在的节点。");
                continue;
            }
            if (edge.source().equals(edge.target())) issue(issues, path, "SELF_EDGE", "不允许自连接。");
            outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
            if (!java.util.Objects.equals(source.parentId(), nodes.get(edge.target()).parentId()))
                issue(issues, path, "CROSS_SCOPE_EDGE", "连线两端必须在同一作用域。");
            if ("if-else".equals(source.type()) || "question-classifier".equals(source.type()))
                validateBranch(edge, source, path, issues);
        }
        for (GraphNode node : nodes.values()) {
            List<GraphEdge> in = incoming.getOrDefault(node.id(), List.of());
            if (node.parentId() == null && !STARTS.contains(node.type()) && in.isEmpty())
                issue(issues, "$.nodes[id=" + node.id() + "]", "DISCONNECTED", "非开始节点必须至少有一个上游。");
        }
        validateScopes(nodes, incoming, outgoing, issues);
        validateAcyclic(nodes, outgoing, issues);
        validateReferences(graph, nodes, incoming, issues);
        return new ValidationResult(issues);
    }

    private void validateConfig(GraphNode node, String path, List<ValidationIssue> issues, Set<String> outputNames) {
        Map<String, Object> config = node.config();
        switch (node.type()) {
            case "user-input", "human-input" -> {
                if ("human-input".equals(node.type()) && blank(string(config.get("prompt"))))
                    issue(issues, path + ".prompt", "REQUIRED", "人工处理说明必填。");
                validateInputFields(node, path, issues);
            }
            case "llm" -> {
                if (blank(string(config.get("model")))) issue(issues, path + ".model", "REQUIRED", "model 必填。");
                if (list(config.get("messages")) == null || list(config.get("messages")).isEmpty())
                    issue(issues, path + ".messages", "REQUIRED", "至少配置一条模型消息。");
            }
            case "agent" -> {
                if (blank(string(config.get("model")))) issue(issues, path + ".model", "REQUIRED", "model 必填。");
                if (blank(string(config.get("instruction")))) issue(issues, path + ".instruction", "REQUIRED", "提示词必填。");
                Object strategy = config.get("strategy");
                if (strategy != null && !"function_call".equals(String.valueOf(strategy)))
                    issue(issues, path + ".strategy", "STRATEGY", "Agent 仅支持 strategy=function_call。");
                if (config.get("maxIterations") instanceof Number max && (max.intValue() < 1 || max.intValue() > 20))
                    issue(issues, path + ".maxIterations", "RANGE", "maxIterations 必须在 1-20 之间。");
                Object tools = config.get("tools");
                if (tools != null) {
                    List<?> configured = list(tools);
                    if (configured == null) issue(issues, path + ".tools", "TOOLS", "tools 必须为数组。");
                    else for (int i = 0; i < configured.size(); i++) {
                        String tool = string(configured.get(i));
                        if (tool == null || !Set.of("calculator", "code").contains(tool))
                            issue(issues, path + ".tools[" + i + "]", "TOOL", "未知工具：" + tool + "。");
                    }
                }
            }
            case "if-else" -> {
                List<?> cases = list(config.get("cases"));
                if (cases == null || cases.isEmpty()) issue(issues, path + ".cases", "REQUIRED", "至少配置一个 IF case。");
                else {
                    Set<String> ids = new HashSet<>();
                    for (int i = 0; i < cases.size(); i++) {
                        if (!(cases.get(i) instanceof Map<?, ?> one)) continue;
                        String id = string(one.get("id"));
                        if (blank(id) || !ids.add(id)) issue(issues, path + ".cases[" + i + "].id", "CASE_ID", "case id 必填且不能重复。");
                        if (list(one.get("conditions")) == null || list(one.get("conditions")).isEmpty())
                            issue(issues, path + ".cases[" + i + "].conditions", "REQUIRED", "每个 case 至少需要一个条件。");
                    }
                }
            }
            case "question-classifier" -> {
                if (blank(string(config.get("model")))) issue(issues, path + ".model", "REQUIRED", "model 必填。");
                if (blank(string(config.get("query")))) issue(issues, path + ".query", "REQUIRED", "query 必填。");
                List<?> classes = list(config.get("classes"));
                if (classes == null || classes.isEmpty()) issue(issues, path + ".classes", "REQUIRED", "至少配置一个类别。");
                else {
                    Set<String> ids = new HashSet<>();
                    for (int i = 0; i < classes.size(); i++) {
                        if (!(classes.get(i) instanceof Map<?, ?> one)) {
                            issue(issues, path + ".classes[" + i + "]", "CLASS", "类别必须为对象。");
                            continue;
                        }
                        String id = string(one.get("id"));
                        if (blank(id) || !ids.add(id)) issue(issues, path + ".classes[" + i + "].id", "CLASS_ID", "类别 id 必填且不能重复。");
                        if (blank(string(one.get("name")))) issue(issues, path + ".classes[" + i + "].name", "REQUIRED", "类别名称必填。");
                    }
                }
                if (config.get("temperature") instanceof Number temperature && (temperature.doubleValue() < 0 || temperature.doubleValue() > 2))
                    issue(issues, path + ".temperature", "RANGE", "temperature 必须在 0-2 之间。");
                if (config.get("topP") instanceof Number topP && (topP.doubleValue() < 0 || topP.doubleValue() > 1))
                    issue(issues, path + ".topP", "RANGE", "topP 必须在 0-1 之间。");
            }
            case "knowledge-retrieval" -> {
                List<?> datasetIds = list(config.get("datasetIds"));
                if (datasetIds == null || datasetIds.isEmpty())
                    issue(issues, path + ".datasetIds", "REQUIRED", "至少选择一个知识库。");
                else for (int i = 0; i < datasetIds.size(); i++) {
                    if (blank(string(datasetIds.get(i))))
                        issue(issues, path + ".datasetIds[" + i + "]", "REQUIRED", "知识库 id 不能为空。");
                }
                if (blank(string(config.get("query")))) issue(issues, path + ".query", "REQUIRED", "query 必填。");
                if (config.get("topK") instanceof Number topK && (topK.intValue() < 1 || topK.intValue() > 20))
                    issue(issues, path + ".topK", "RANGE", "topK 必须在 1-20 之间。");
                if (config.get("scoreThreshold") instanceof Number threshold
                        && (threshold.doubleValue() < 0 || threshold.doubleValue() > 1))
                    issue(issues, path + ".scoreThreshold", "RANGE", "scoreThreshold 必须在 0-1 之间。");
            }
            case "code" -> {
                if (!Set.of("python3", "javascript").contains(string(config.get("language"))))
                    issue(issues, path + ".language", "LANGUAGE", "仅支持 python3 或 javascript。");
                if (blank(string(config.get("code")))) issue(issues, path + ".code", "REQUIRED", "code 必填。");
                if (!(config.get("outputs") instanceof Map<?, ?>)) issue(issues, path + ".outputs", "REQUIRED", "必须声明输出类型。");
            }
            case "output" -> {
                List<?> mappings = list(config.get("mappings"));
                if (mappings == null || mappings.isEmpty()) issue(issues, path + ".mappings", "REQUIRED", "至少定义一个输出映射。");
                else for (int i = 0; i < mappings.size(); i++) {
                    if (!(mappings.get(i) instanceof Map<?, ?> mapping)) continue;
                    String name = string(mapping.get("name"));
                    if (blank(name) || !outputNames.add(name))
                        issue(issues, path + ".mappings[" + i + "].name", "DUPLICATE_OUTPUT", "输出名称必填且全局不能重复。");
                    if (blank(string(mapping.get("selector")))) issue(issues, path + ".mappings[" + i + "].selector", "REQUIRED", "selector 必填。");
                }
            }
            case "answer" -> {
                if (!(config.get("answer") instanceof String)) issue(issues, path + ".answer", "REQUIRED", "answer 模板必填。");
            }
            case "template" -> {
                if (blank(string(config.get("template")))) issue(issues, path + ".template", "REQUIRED", "模板必填。");
                List<?> variables = list(config.get("variables"));
                if (variables != null) for (int i = 0; i < variables.size(); i++) {
                    if (!(variables.get(i) instanceof Map<?, ?> variable)) continue;
                    String itemPath = path + ".variables[" + i + "]";
                    if (blank(string(variable.get("variable")))) issue(issues, itemPath + ".variable", "REQUIRED", "变量名必填。");
                    if (blank(string(variable.get("selector")))) issue(issues, itemPath + ".selector", "REQUIRED", "selector 必填。");
                }
            }
            case "variable-aggregator" -> {
                List<?> variables = list(config.get("variables"));
                if (variables == null || variables.isEmpty())
                    issue(issues, path + ".variables", "REQUIRED", "至少配置一个聚合变量。");
                else for (int i = 0; i < variables.size(); i++) {
                    if (variables.get(i) instanceof Map<?, ?> variable && blank(string(variable.get("selector"))))
                        issue(issues, path + ".variables[" + i + "].selector", "REQUIRED", "selector 必填。");
                }
            }
            case "variable-assigner" -> {
                String name = string(config.get("variable"));
                if (blank(name)) issue(issues, path + ".variable", "REQUIRED", "变量名必填。");
                else if (!name.matches("[\\w-]+")) issue(issues, path + ".variable", "VARIABLE_NAME", "变量名只能包含字母、数字、下划线和短横线。");
            }
            case "list-operator" -> {
                if (blank(string(config.get("inputSelector"))))
                    issue(issues, path + ".inputSelector", "REQUIRED", "inputSelector 必填。");
                if (config.get("extractSerial") instanceof Number serial && serial.intValue() < 0)
                    issue(issues, path + ".extractSerial", "RANGE", "extractSerial 不能为负数。");
                if (config.get("limit") instanceof Number limit && limit.intValue() < 0)
                    issue(issues, path + ".limit", "RANGE", "limit 不能为负数。");
            }
            case "iteration" -> {
                if (blank(string(config.get("inputSelector"))))
                    issue(issues, path + ".inputSelector", "REQUIRED", "迭代输入 selector 必填。");
                if (config.get("maxParallelism") instanceof Number max && (max.intValue() < 1 || max.intValue() > 32))
                    issue(issues, path + ".maxParallelism", "RANGE", "maxParallelism 必须在 1-32 之间。");
            }
            case "loop" -> {
                if (!(config.get("maxIterations") instanceof Number max) || max.intValue() < 1 || max.intValue() > 100)
                    issue(issues, path + ".maxIterations", "RANGE", "maxIterations 必须在 1-100 之间。");
                validateLogicalOperator(config.get("breakLogicalOperator"), path + ".breakLogicalOperator", issues);
                validateConditions(list(config.get("breakConditions")), path + ".breakConditions", issues);
            }
            case "exit-loop" -> {
                validateLogicalOperator(config.get("logicalOperator"), path + ".logicalOperator", issues);
                validateConditions(list(config.get("conditions")), path + ".conditions", issues);
            }
            case "http-request" -> {
                String method = string(config.get("method")).toUpperCase();
                if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD").contains(method))
                    issue(issues, path + ".method", "METHOD", "不支持的 HTTP 方法。");
                if (blank(string(config.get("url")))) issue(issues, path + ".url", "REQUIRED", "url 必填。");
                if (config.get("timeoutMillis") instanceof Number timeout
                        && (timeout.intValue() < 1000 || timeout.intValue() > 60000))
                    issue(issues, path + ".timeoutMillis", "RANGE", "timeoutMillis 必须在 1000-60000 之间。");
            }
            default -> { }
        }
    }

    private void validateInputFields(GraphNode node, String path, List<ValidationIssue> issues) {
        List<?> fields = list(node.config().get("fields"));
        if (fields == null || fields.isEmpty()) {
            issue(issues, path + ".fields", "REQUIRED", "至少定义一个输入字段。");
            return;
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            if (!(fields.get(i) instanceof Map<?, ?> field)) {
                issue(issues, path + ".fields[" + i + "]", "TYPE", "输入字段必须为对象。");
                continue;
            }
            String name = string(field.get("variable"));
            if (blank(name) || !names.add(name))
                issue(issues, path + ".fields[" + i + "].variable", "INPUT_NAME", "输入变量名必填且不能重复。");
            if (!Set.of("text", "paragraph", "number", "select", "boolean", "json").contains(string(field.get("type"))))
                issue(issues, path + ".fields[" + i + "].type", "INPUT_TYPE", "不支持的输入类型。");
        }
    }

    private void validateLogicalOperator(Object value, String path, List<ValidationIssue> issues) {
        if (value != null && !Set.of("and", "or").contains(String.valueOf(value)))
            issue(issues, path, "LOGICAL_OPERATOR", "条件组合方式只能是 and 或 or。");
    }

    private void validateConditions(List<?> conditions, String path, List<ValidationIssue> issues) {
        if (conditions == null) return;
        for (int i = 0; i < conditions.size(); i++) {
            String itemPath = path + "[" + i + "]";
            if (!(conditions.get(i) instanceof Map<?, ?> condition)) {
                issue(issues, itemPath, "TYPE", "条件必须为对象。");
                continue;
            }
            if (blank(string(condition.get("variable")))) issue(issues, itemPath + ".variable", "REQUIRED", "条件变量必填。");
            String operator = string(condition.get("operator"));
            if (operator == null || !CONDITION_OPERATORS.contains(operator))
                issue(issues, itemPath + ".operator", "OPERATOR", "不支持的条件运算符。");
        }
    }

    private void validateBranch(GraphEdge edge, GraphNode source, String path, List<ValidationIssue> issues) {
        boolean classifier = "question-classifier".equals(source.type());
        String handle = edge.sourceHandle();
        if (blank(handle)) {
            issue(issues, path + ".sourceHandle", "BRANCH_HANDLE",
                    classifier ? "分类器出边必须指定 class id。" : "IF 出边必须指定 case id 或 else。");
            return;
        }
        if (!classifier && "else".equals(handle)) return;
        String field = classifier ? "classes" : "cases";
        boolean found = list(source.config().get(field)) != null && list(source.config().get(field)).stream()
                .filter(Map.class::isInstance).map(Map.class::cast).anyMatch(item -> handle.equals(string(item.get("id"))));
        if (!found) issue(issues, path + ".sourceHandle", "BRANCH_HANDLE",
                classifier ? "sourceHandle 未匹配任何 class id。" : "sourceHandle 未匹配任何 case id。");
    }

    private void validateScopes(Map<String, GraphNode> nodes, Map<String, List<GraphEdge>> incoming,
                                Map<String, List<GraphEdge>> outgoing, List<ValidationIssue> issues) {
        Map<String, List<GraphNode>> children = new HashMap<>();
        for (GraphNode node : nodes.values()) {
            if (node.parentId() == null) {
                if ("exit-loop".equals(node.type()))
                    issue(issues, "$.nodes[id=" + node.id() + "]", "EXIT_LOOP_SCOPE", "Exit Loop 只能位于 Loop 作用域内。");
                continue;
            }
            GraphNode parent = nodes.get(node.parentId());
            if (parent == null) {
                issue(issues, "$.nodes[id=" + node.id() + "].parentId", "UNKNOWN_SCOPE", "所属作用域节点不存在。");
                continue;
            }
            if (!SCOPES.contains(parent.type())) {
                issue(issues, "$.nodes[id=" + node.id() + "].parentId", "INVALID_SCOPE", "节点只能归属于 Iteration 或 Loop。");
                continue;
            }
            if (SCOPES.contains(node.type()) || parent.parentId() != null)
                issue(issues, "$.nodes[id=" + node.id() + "].parentId", "NESTED_SCOPE", "当前版本不支持嵌套作用域。");
            if (SCOPE_FORBIDDEN.contains(node.type()))
                issue(issues, "$.nodes[id=" + node.id() + "]", "SCOPE_FORBIDDEN", "该节点不能放在作用域内。");
            if ("exit-loop".equals(node.type()) && !"loop".equals(parent.type()))
                issue(issues, "$.nodes[id=" + node.id() + "]", "EXIT_LOOP_SCOPE", "Exit Loop 只能位于 Loop 作用域内。");
            children.computeIfAbsent(parent.id(), ignored -> new ArrayList<>()).add(node);
        }
        for (GraphNode container : nodes.values()) {
            if (!SCOPES.contains(container.type())) continue;
            List<GraphNode> members = children.getOrDefault(container.id(), List.of());
            if (members.isEmpty()) {
                issue(issues, "$.nodes[id=" + container.id() + "]", "EMPTY_SCOPE",
                        container.title() + " 至少需要一个作用域内节点。");
                continue;
            }
            List<GraphNode> entries = members.stream().filter(node -> incoming.getOrDefault(node.id(), List.of()).stream()
                    .noneMatch(edge -> {
                        GraphNode source = nodes.get(edge.source());
                        return source != null && container.id().equals(source.parentId());
                    })).toList();
            if (entries.size() != 1) {
                issue(issues, "$.nodes[id=" + container.id() + "]", "SCOPE_ENTRY", "作用域必须且只能有一个入口节点。");
                continue;
            }
            Set<String> reachable = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(entries.getFirst().id());
            while (!queue.isEmpty()) {
                String id = queue.remove();
                if (!reachable.add(id)) continue;
                for (GraphEdge edge : outgoing.getOrDefault(id, List.of())) {
                    GraphNode target = nodes.get(edge.target());
                    if (target != null && container.id().equals(target.parentId())) queue.add(target.id());
                }
            }
            if (reachable.size() < members.size())
                issue(issues, "$.nodes[id=" + container.id() + "]", "SCOPE_DISCONNECTED", "作用域内存在未连接到入口的节点。");
        }
    }

    private void validateAcyclic(Map<String, GraphNode> nodes, Map<String, List<GraphEdge>> outgoing, List<ValidationIssue> issues) {
        Map<String, Integer> indegree = new HashMap<>();
        nodes.keySet().forEach(id -> indegree.put(id, 0));
        outgoing.values().stream().flatMap(List::stream).forEach(edge -> indegree.computeIfPresent(edge.target(), (k, v) -> v + 1));
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> { if (degree == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.remove();
            visited++;
            for (GraphEdge edge : outgoing.getOrDefault(id, List.of())) {
                int degree = indegree.computeIfPresent(edge.target(), (k, v) -> v - 1);
                if (degree == 0) queue.add(edge.target());
            }
        }
        if (visited != nodes.size()) issue(issues, "$.edges", "CYCLE", "图不能包含环。");
    }

    private void validateReferences(GraphDocument graph, Map<String, GraphNode> nodes,
                                    Map<String, List<GraphEdge>> incoming, List<ValidationIssue> issues) {
        for (GraphNode node : nodes.values()) {
            Set<String> ancestors = ancestorsOf(node.id(), incoming);
            if (node.parentId() != null) ancestors.addAll(ancestorsOf(node.parentId(), incoming));
            if (SCOPES.contains(node.type()))
                nodes.values().stream().filter(child -> node.id().equals(child.parentId()))
                        .forEach(child -> ancestors.add(child.id()));
            scanConfig(node.config(), "$.nodes[id=" + node.id() + "].config", node.id(), nodes, ancestors, issues);
        }
    }

    private Set<String> ancestorsOf(String nodeId, Map<String, List<GraphEdge>> incoming) {
        Set<String> ancestors = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        incoming.getOrDefault(nodeId, List.of()).forEach(edge -> queue.add(edge.source()));
        while (!queue.isEmpty()) {
            String id = queue.remove();
            if (ancestors.add(id)) incoming.getOrDefault(id, List.of()).forEach(edge -> queue.add(edge.source()));
        }
        return ancestors;
    }

    private void scanConfig(Object value, String path, String self, Map<String, GraphNode> nodes,
                            Set<String> ancestors, List<ValidationIssue> issues) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> {
                String childPath = path + "." + key;
                if (child instanceof String text && ("selector".equals(key) || "inputSelector".equals(key) ||
                        "outputSelector".equals(key) || ("variable".equals(key) && path.toLowerCase().contains("conditions")))
                        && text.matches("[\\w-]+\\.[\\w.-]+"))
                    checkReference(text, childPath, self, nodes, ancestors, issues);
                else scanConfig(child, childPath, self, nodes, ancestors, issues);
            });
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) scanConfig(list.get(i), path + "[" + i + "]", self, nodes, ancestors, issues);
        } else if (value instanceof String text) {
            Matcher matcher = TEMPLATE.matcher(text);
            while (matcher.find()) checkReference(matcher.group(1) + "." + matcher.group(2), path, self, nodes, ancestors, issues);
        }
    }

    private void checkReference(String selector, String path, String self, Map<String, GraphNode> nodes,
                                Set<String> ancestors, List<ValidationIssue> issues) {
        String nodeId = selector.substring(0, selector.indexOf('.'));
        if (!nodes.containsKey(nodeId)) issue(issues, path, "UNKNOWN_VARIABLE", "变量引用了不存在的节点 " + nodeId + "。");
        else if (nodeId.equals(self) || !ancestors.contains(nodeId))
            issue(issues, path, "INVALID_VARIABLE", "变量只能引用当前节点的上游：" + selector + "。");
    }

    private static String string(Object value) { return value instanceof String text ? text : null; }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : null; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void issue(List<ValidationIssue> issues, String path, String code, String message) {
        issues.add(new ValidationIssue(path, code, message));
    }
}
