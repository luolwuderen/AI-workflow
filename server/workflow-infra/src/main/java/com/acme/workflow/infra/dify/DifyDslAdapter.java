package com.acme.workflow.infra.dify;

import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphEdge;
import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.Position;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DifyDslAdapter {
    public static final String DSL_VERSION = "0.4.0";
    private static final Pattern DIFY_TEMPLATE = Pattern.compile("\\{\\{#([\\w-]+)\\.([\\w.-]+)#}}");
    private static final Pattern GRAPH_TEMPLATE = Pattern.compile("\\{\\{\\s*([\\w-]+)\\.([\\w.-]+)\\s*}}");
    private static final Set<String> ROOT_FIELDS = Set.of("app", "kind", "version", "workflow", "dependencies");
    private static final Set<String> APP_FIELDS = Set.of("name", "description", "icon", "icon_background", "mode", "use_icon_as_answer_icon");
    private static final Set<String> NODE_FIELDS = Set.of("id", "type", "title", "position", "positionAbsolute", "data", "width", "height",
            "sourcePosition", "targetPosition", "selected", "parentId");
    private static final Set<String> INTERNAL_STARTS = Set.of("iteration-start", "loop-start");
    private static final Set<String> EDGE_FIELDS = Set.of("id", "type", "source", "sourceHandle", "target", "targetHandle", "data", "zIndex");
    private final ObjectMapper yaml = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE).build());

    public GraphDocument importYaml(String text) {
        Map<String, Object> root;
        try { root = yaml.readValue(text, new TypeReference<>() { }); }
        catch (Exception error) { throw new DslException("$", "YAML 无法解析：" + error.getMessage()); }
        if (root == null) throw new DslException("$", "DSL 不能为空。");
        allowed(root, ROOT_FIELDS, "$");
        if (!"app".equals(root.get("kind"))) throw new DslException("$.kind", "仅支持 kind=app。");
        if (!DSL_VERSION.equals(String.valueOf(root.get("version"))))
            throw new DslException("$.version", "仅支持 Dify DSL " + DSL_VERSION + "，实际为 " + root.get("version") + "。");
        Map<String, Object> app = object(root.get("app"), "$.app");
        allowed(app, APP_FIELDS, "$.app");
        AppType appType = switch (String.valueOf(app.get("mode"))) {
            case "workflow" -> AppType.WORKFLOW;
            case "advanced-chat" -> AppType.CHATFLOW;
            default -> throw new DslException("$.app.mode", "仅支持 workflow 或 advanced-chat。");
        };
        Map<String, Object> workflow = object(root.get("workflow"), "$.workflow");
        allowed(workflow, Set.of("graph", "features", "environment_variables", "conversation_variables"), "$.workflow");
        if (root.containsKey("dependencies")) throw new DslException("$.dependencies", "第一期不支持 dependencies。");
        empty(workflow.get("environment_variables"), "$.workflow.environment_variables");
        empty(workflow.get("conversation_variables"), "$.workflow.conversation_variables");
        empty(workflow.get("features"), "$.workflow.features");
        Map<String, Object> difyGraph = object(workflow.get("graph"), "$.workflow.graph");
        allowed(difyGraph, Set.of("nodes", "edges", "viewport"), "$.workflow.graph");
        List<GraphNode> nodes = new ArrayList<>();
        Set<String> internalStarts = new java.util.HashSet<>();
        for (Object raw : array(difyGraph.get("nodes"), "$.workflow.graph.nodes")) {
            String path = "$.workflow.graph.nodes[" + nodes.size() + "]";
            Map<String, Object> node = object(raw, path);
            Object data = node.get("data");
            String difyType = data instanceof Map<?, ?> map ? String.valueOf(map.get("type")) : null;
            if (INTERNAL_STARTS.contains(difyType)) {
                internalStarts.add(requiredString(node.get("id"), path + ".id"));
                continue;
            }
            nodes.add(importNode(node, path));
        }
        Map<String, String> types = new LinkedHashMap<>();
        nodes.forEach(node -> types.put(node.id(), node.type()));
        List<GraphEdge> edges = new ArrayList<>();
        for (Object raw : array(difyGraph.get("edges"), "$.workflow.graph.edges")) {
            String path = "$.workflow.graph.edges[" + edges.size() + "]";
            Map<String, Object> edge = object(raw, path);
            allowed(edge, EDGE_FIELDS, path);
            if (internalStarts.contains(String.valueOf(edge.get("source")))
                    || internalStarts.contains(String.valueOf(edge.get("target")))) continue;
            edges.add(importEdge(edge, path, types));
        }
        Map<String, Object> viewport = difyGraph.get("viewport") == null ? null : object(difyGraph.get("viewport"), "$.workflow.graph.viewport");
        if (viewport != null) allowed(viewport, Set.of("x", "y", "zoom"), "$.workflow.graph.viewport");
        return new GraphDocument(1, appType, nodes, edges, viewport);
    }

    public String exportYaml(GraphDocument graph, String appName) {
        try {
            Map<String, Object> app = map("name", appName == null ? "Workflow App" : appName,
                    "description", "", "icon", "🤖", "icon_background", "#FFEAD5",
                    "mode", graph.appType() == AppType.CHATFLOW ? "advanced-chat" : "workflow", "use_icon_as_answer_icon", false);
            List<Map<String, Object>> nodes = graph.nodes().stream().map(this::exportNode).toList();
            Map<String, String> types = new LinkedHashMap<>();
            Map<String, String> parents = new LinkedHashMap<>();
            graph.nodes().forEach(node -> {
                types.put(node.id(), node.type());
                if (node.parentId() != null) parents.put(node.id(), node.parentId());
            });
            List<Map<String, Object>> edges = graph.edges().stream().map(edge -> exportEdge(edge, types, parents)).toList();
            Map<String, Object> difyGraph = map("nodes", nodes, "edges", edges,
                    "viewport", graph.viewport() == null ? map("x", 0, "y", 0, "zoom", 1) : graph.viewport());
            Map<String, Object> workflow = map("conversation_variables", List.of(), "environment_variables", List.of(),
                    "features", map(), "graph", difyGraph);
            return yaml.writeValueAsString(map("app", app, "kind", "app", "version", DSL_VERSION, "workflow", workflow));
        } catch (DslException error) {
            throw error;
        } catch (Exception error) {
            throw new DslException("$", "YAML 导出失败：" + error.getMessage());
        }
    }

    private GraphNode importNode(Map<String, Object> raw, String path) {
        allowed(raw, NODE_FIELDS, path);
        String id = requiredString(raw.get("id"), path + ".id");
        Map<String, Object> data = object(raw.get("data"), path + ".data");
        String difyType = requiredString(data.get("type"), path + ".data.type");
        String title = data.get("title") == null ? difyType : String.valueOf(data.get("title"));
        String type = switch (difyType) {
            case "start" -> "user-input";
            case "end" -> "output";
            case "llm", "if-else", "code", "answer", "iteration", "loop", "exit-loop", "human-input", "agent" -> difyType;
            case "template-transform" -> "template";
            case "variable-aggregator" -> "variable-aggregator";
            case "list-operator" -> "list-operator";
            default -> throw new DslException(path + ".data.type", "当前 DSL 不支持节点 " + difyType + "。");
        };
        Map<String, Object> config = switch (type) {
            case "user-input" -> importStart(data, path + ".data");
            case "output" -> importOutput(data, path + ".data");
            case "llm" -> importLlm(data, path + ".data");
            case "if-else" -> importIf(data, path + ".data");
            case "code" -> importCode(data, path + ".data");
            case "answer" -> importAnswer(data, path + ".data");
            case "template" -> importTemplate(data, path + ".data");
            case "variable-aggregator" -> importAggregator(data, path + ".data");
            case "list-operator" -> importListOperator(data, path + ".data");
            case "iteration" -> importIteration(data, path + ".data");
            case "loop" -> importLoop(data, path + ".data");
            case "exit-loop" -> importExitLoop(data, path + ".data");
            case "human-input" -> importHumanInput(data, path + ".data");
            case "agent" -> importAgent(data, path + ".data");
            default -> throw new AssertionError();
        };
        Position position = position(raw.get("position"), path + ".position");
        String parentId = raw.get("parentId") == null ? null : String.valueOf(raw.get("parentId"));
        return new GraphNode(id, type, title, position, config, parentId);
    }

    private Map<String, Object> importIteration(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "iterator_selector", "output_selector",
                "parallel", "is_parallel", "start_node_id", "width", "height"), path);
        String outputSelector = optionalSelector(data.get("output_selector"), path + ".output_selector");
        return map("inputSelector", selector(data.get("iterator_selector"), path + ".iterator_selector"),
                "outputSelector", outputSelector,
                "parallel", Boolean.TRUE.equals(data.get("parallel")) || Boolean.TRUE.equals(data.get("is_parallel")),
                "maxParallelism", 4);
    }

    private Map<String, Object> importLoop(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "loop_count", "break_conditions",
                "logical_operator", "start_node_id", "output_selector", "loop_variables", "width", "height"), path);
        if (data.get("loop_variables") instanceof List<?> variables && !variables.isEmpty())
            throw new DslException(path + ".loop_variables", "暂不支持 loop_variables，请改用 Variable Assigner。");
        String outputSelector = optionalSelector(data.get("output_selector"), path + ".output_selector");
        return map("maxIterations", data.get("loop_count") instanceof Number count ? count.intValue() : 10,
                "outputSelector", outputSelector,
                "breakConditions", data.get("break_conditions") == null ? List.of()
                        : importConditions(data.get("break_conditions"), path + ".break_conditions"),
                "breakLogicalOperator", logical(data.get("logical_operator"), path + ".logical_operator"));
    }

    private Map<String, Object> importExitLoop(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "conditions", "logical_operator"), path);
        return map("conditions", data.get("conditions") == null ? List.of()
                        : importConditions(data.get("conditions"), path + ".conditions"),
                "logicalOperator", logical(data.get("logical_operator"), path + ".logical_operator"));
    }

    private Map<String, Object> importStart(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "variables"), path);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Object raw : array(data.get("variables"), path + ".variables")) {
            String itemPath = path + ".variables[" + fields.size() + "]";
            Map<String, Object> variable = object(raw, itemPath);
            allowed(variable, Set.of("variable", "label", "type", "required", "default", "options"), itemPath);
            String type = switch (String.valueOf(variable.get("type"))) {
                case "text-input", "paragraph" -> "text";
                case "number", "select", "json" -> String.valueOf(variable.get("type"));
                case "checkbox" -> "boolean";
                default -> throw new DslException(itemPath + ".type", "不支持的输入类型 " + variable.get("type") + "。");
            };
            Map<String, Object> field = map("variable", requiredString(variable.get("variable"), itemPath + ".variable"),
                    "label", variable.getOrDefault("label", variable.get("variable")), "type", type,
                    "required", variable.getOrDefault("required", false));
            if (variable.containsKey("default")) field.put("default", variable.get("default"));
            if (variable.containsKey("options")) field.put("options", variable.get("options"));
            fields.add(field);
        }
        return map("fields", fields);
    }

    private Map<String, Object> importHumanInput(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "form_content", "inputs",
                "user_actions", "timeout", "timeout_unit"), path);
        if (data.get("user_actions") != null && !array(data.get("user_actions"), path + ".user_actions").isEmpty())
            throw new DslException(path + ".user_actions", "暂不支持 Human Input 动作分支。");
        if (data.containsKey("timeout") && (!(data.get("timeout") instanceof Number timeout) || timeout.doubleValue() != 36))
            throw new DslException(path + ".timeout", "仅支持 Dify 默认的 36 小时超时，当前运行不支持超时策略。");
        if (data.containsKey("timeout_unit") && !"hour".equals(String.valueOf(data.get("timeout_unit"))))
            throw new DslException(path + ".timeout_unit", "仅支持 Dify 默认的 hour 超时单位。");

        List<Map<String, Object>> fields = new ArrayList<>();
        for (Object raw : array(data.get("inputs"), path + ".inputs")) {
            String itemPath = path + ".inputs[" + fields.size() + "]";
            Map<String, Object> input = object(raw, itemPath);
            allowed(input, Set.of("output_variable_name", "type", "required", "default", "option_source"), itemPath);
            String variable = requiredString(input.get("output_variable_name"), itemPath + ".output_variable_name");
            String type = switch (String.valueOf(input.get("type"))) {
                case "text-input" -> "text";
                case "paragraph", "number", "select", "json" -> String.valueOf(input.get("type"));
                case "checkbox" -> "boolean";
                default -> throw new DslException(itemPath + ".type", "不支持的 Human Input 类型 " + input.get("type") + "。");
            };
            if (input.containsKey("required") && !Boolean.TRUE.equals(input.get("required")))
                throw new DslException(itemPath + ".required", "Dify Human Input 字段均为必填，不能映射为可选字段。");
            Map<String, Object> field = map("variable", variable, "label", variable, "type", type, "required", true);
            if (input.get("default") != null) {
                if (!Set.of("text", "paragraph").contains(type))
                    throw new DslException(itemPath + ".default", "当前仅支持文本与段落字段的常量默认值。");
                Map<String, Object> defaultValue = object(input.get("default"), itemPath + ".default");
                allowed(defaultValue, Set.of("type", "selector", "value"), itemPath + ".default");
                requireConstantSource(defaultValue, itemPath + ".default");
                if (!(defaultValue.get("value") instanceof String value))
                    throw new DslException(itemPath + ".default.value", "文本默认值必须为字符串。");
                field.put("default", value);
            }
            if ("select".equals(type)) {
                Map<String, Object> source = object(input.get("option_source"), itemPath + ".option_source");
                allowed(source, Set.of("type", "selector", "value"), itemPath + ".option_source");
                requireConstantSource(source, itemPath + ".option_source");
                field.put("options", array(source.get("value"), itemPath + ".option_source.value"));
            } else if (input.containsKey("option_source")) {
                throw new DslException(itemPath + ".option_source", "仅 select 字段支持 option_source。");
            }
            fields.add(field);
        }
        return map("prompt", fromDifyTemplate(requiredString(data.get("form_content"), path + ".form_content")),
                "fields", fields);
    }

    private static void requireConstantSource(Map<String, Object> source, String path) {
        if (!"constant".equals(String.valueOf(source.get("type"))))
            throw new DslException(path + ".type", "暂不支持变量来源，仅支持 constant。");
        if (source.get("selector") != null && !array(source.get("selector"), path + ".selector").isEmpty())
            throw new DslException(path + ".selector", "constant 来源的 selector 必须为空。");
    }

    private Map<String, Object> importOutput(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "outputs"), path);
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (Object raw : array(data.get("outputs"), path + ".outputs")) {
            String itemPath = path + ".outputs[" + mappings.size() + "]";
            Map<String, Object> output = object(raw, itemPath);
            allowed(output, Set.of("variable", "value_selector"), itemPath);
            mappings.add(map("name", requiredString(output.get("variable"), itemPath + ".variable"),
                    "selector", selector(output.get("value_selector"), itemPath + ".value_selector")));
        }
        return map("mappings", mappings);
    }

    private Map<String, Object> importLlm(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "model", "prompt_template"), path);
        Map<String, Object> model = object(data.get("model"), path + ".model");
        allowed(model, Set.of("name", "provider", "mode", "completion_params"), path + ".model");
        if (!"chat".equals(model.get("mode"))) throw new DslException(path + ".model.mode", "仅支持 chat 模式。");
        String provider = String.valueOf(model.get("provider"));
        if (!Set.of("openai", "langgenius/openai/openai").contains(provider))
            throw new DslException(path + ".model.provider", "核心 DSL 仅支持 OpenAI 兼容 provider。");
        Map<String, Object> config = map("model", requiredString(model.get("name"), path + ".model.name"));
        Map<String, Object> params = model.get("completion_params") == null ? map() : object(model.get("completion_params"), path + ".model.completion_params");
        allowed(params, Set.of("temperature", "top_p", "max_tokens", "stop", "response_format"), path + ".model.completion_params");
        copy(params, "temperature", config, "temperature");
        copy(params, "top_p", config, "topP");
        copy(params, "max_tokens", config, "maxTokens");
        copy(params, "stop", config, "stop");
        copy(params, "response_format", config, "responseFormat");
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Object raw : array(data.get("prompt_template"), path + ".prompt_template")) {
            String itemPath = path + ".prompt_template[" + messages.size() + "]";
            Map<String, Object> prompt = object(raw, itemPath);
            allowed(prompt, Set.of("role", "text"), itemPath);
            String role = String.valueOf(prompt.get("role"));
            if (!Set.of("system", "user", "assistant").contains(role)) throw new DslException(itemPath + ".role", "不支持的角色。");
            messages.add(map("role", role, "content", fromDifyTemplate(requiredString(prompt.get("text"), itemPath + ".text"))));
        }
        config.put("messages", messages);
        return config;
    }

    private Map<String, Object> importAgent(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "strategy", "instruction", "model", "tools", "vision"), path);
        String strategy = String.valueOf(data.getOrDefault("strategy", "function_call"));
        if (!"function_call".equals(strategy))
            throw new DslException(path + ".strategy", "Agent 仅支持 function_call 策略，不支持 " + strategy + "（如 ReAct）。");
        if (data.get("vision") != null) {
            Map<String, Object> vision = object(data.get("vision"), path + ".vision");
            allowed(vision, Set.of("enabled"), path + ".vision");
            if (!Boolean.FALSE.equals(vision.get("enabled")))
                throw new DslException(path + ".vision", "暂不支持 Agent 视觉能力，vision.enabled 必须为 false。");
        }
        Map<String, Object> model = object(data.get("model"), path + ".model");
        allowed(model, Set.of("name", "provider", "mode", "completion_params"), path + ".model");
        if (!"chat".equals(model.get("mode"))) throw new DslException(path + ".model.mode", "仅支持 chat 模式。");
        String provider = String.valueOf(model.get("provider"));
        if (!Set.of("openai", "langgenius/openai/openai").contains(provider))
            throw new DslException(path + ".model.provider", "核心 DSL 仅支持 OpenAI 兼容 provider。");
        Map<String, Object> config = map("model", requiredString(model.get("name"), path + ".model.name"),
                "instruction", fromDifyTemplate(requiredString(data.get("instruction"), path + ".instruction")),
                "strategy", "function_call");
        Map<String, Object> params = model.get("completion_params") == null ? map()
                : object(model.get("completion_params"), path + ".model.completion_params");
        allowed(params, Set.of("temperature", "top_p", "max_tokens"), path + ".model.completion_params");
        copy(params, "temperature", config, "temperature");
        copy(params, "top_p", config, "topP");
        copy(params, "max_tokens", config, "maxTokens");
        List<String> tools = new ArrayList<>();
        if (data.get("tools") != null) for (Object raw : array(data.get("tools"), path + ".tools")) {
            String itemPath = path + ".tools[" + tools.size() + "]";
            Map<String, Object> tool = object(raw, itemPath);
            allowed(tool, Set.of("provider_id", "provider_type", "tool_name", "tool_label", "type", "enabled"), itemPath);
            String name = requiredString(tool.get("tool_name"), itemPath + ".tool_name");
            if (!"calculator".equals(name))
                throw new DslException(itemPath + ".tool_name", "暂不支持工具 " + name + "，仅内置 calculator。");
            tools.add(name);
        }
        config.put("tools", tools);
        return config;
    }

    private Map<String, Object> importIf(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "cases", "logical_operator", "conditions"), path);
        List<Map<String, Object>> cases = new ArrayList<>();
        if (data.containsKey("cases")) {
            for (Object raw : array(data.get("cases"), path + ".cases")) {
                String itemPath = path + ".cases[" + cases.size() + "]";
                Map<String, Object> one = object(raw, itemPath);
                allowed(one, Set.of("case_id", "logical_operator", "conditions"), itemPath);
                cases.add(map("id", requiredString(one.get("case_id"), itemPath + ".case_id"),
                        "logicalOperator", logical(one.get("logical_operator"), itemPath + ".logical_operator"),
                        "conditions", importConditions(one.get("conditions"), itemPath + ".conditions")));
            }
        } else {
            cases.add(map("id", "case-1", "logicalOperator", logical(data.get("logical_operator"), path + ".logical_operator"),
                    "conditions", importConditions(data.get("conditions"), path + ".conditions")));
        }
        return map("cases", cases);
    }

    private List<Map<String, Object>> importConditions(Object raw, String path) {
        List<Map<String, Object>> conditions = new ArrayList<>();
        for (Object item : array(raw, path)) {
            String itemPath = path + "[" + conditions.size() + "]";
            Map<String, Object> condition = object(item, itemPath);
            allowed(condition, Set.of("id", "comparison_operator", "value", "varType", "variable_selector"), itemPath);
            String varType = String.valueOf(condition.get("varType"));
            if (!Set.of("string", "number", "boolean", "object", "array[string]", "array[number]").contains(varType))
                throw new DslException(itemPath + ".varType", "不支持的变量类型 " + varType + "。");
            conditions.add(map("variable", selector(condition.get("variable_selector"), itemPath + ".variable_selector"),
                    "operator", operator(requiredString(condition.get("comparison_operator"), itemPath + ".comparison_operator")),
                    "value", condition.get("value")));
        }
        return conditions;
    }

    private Map<String, Object> importCode(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "code_language", "code", "variables", "outputs"), path);
        String language = String.valueOf(data.get("code_language"));
        if (!Set.of("python3", "javascript").contains(language)) throw new DslException(path + ".code_language", "仅支持 python3/javascript。");
        List<Map<String, Object>> inputs = new ArrayList<>();
        if (data.containsKey("variables")) for (Object raw : array(data.get("variables"), path + ".variables")) {
            String itemPath = path + ".variables[" + inputs.size() + "]";
            Map<String, Object> variable = object(raw, itemPath);
            allowed(variable, Set.of("variable", "value_selector"), itemPath);
            inputs.add(map("name", requiredString(variable.get("variable"), itemPath + ".variable"),
                    "selector", selector(variable.get("value_selector"), itemPath + ".value_selector")));
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        Map<String, Object> rawOutputs = object(data.get("outputs"), path + ".outputs");
        for (Map.Entry<String, Object> entry : rawOutputs.entrySet()) {
            Map<String, Object> declaration = object(entry.getValue(), path + ".outputs." + entry.getKey());
            allowed(declaration, Set.of("type", "children"), path + ".outputs." + entry.getKey());
            if (declaration.get("children") != null) throw new DslException(path + ".outputs." + entry.getKey() + ".children", "嵌套输出暂不支持。");
            outputs.put(entry.getKey(), map("type", requiredString(declaration.get("type"), path + ".outputs." + entry.getKey() + ".type")));
        }
        return map("language", language, "code", requiredString(data.get("code"), path + ".code"), "inputs", inputs, "outputs", outputs);
    }

    private Map<String, Object> importAnswer(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "answer"), path);
        return map("answer", fromDifyTemplate(requiredString(data.get("answer"), path + ".answer")));
    }

    private Map<String, Object> importTemplate(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "template", "variables"), path);
        List<Map<String, Object>> variables = new ArrayList<>();
        for (Object raw : array(data.get("variables"), path + ".variables")) {
            String itemPath = path + ".variables[" + variables.size() + "]";
            Map<String, Object> variable = object(raw, itemPath);
            allowed(variable, Set.of("variable", "value_selector"), itemPath);
            variables.add(map("variable", requiredString(variable.get("variable"), itemPath + ".variable"),
                    "selector", selector(variable.get("value_selector"), itemPath + ".value_selector")));
        }
        return map("template", fromDifyTemplate(requiredString(data.get("template"), path + ".template")),
                "variables", variables);
    }

    private Map<String, Object> importAggregator(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "variables", "output_type", "advanced_settings"), path);
        if (data.get("advanced_settings") != null && !object(data.get("advanced_settings"), path + ".advanced_settings").isEmpty())
            throw new DslException(path + ".advanced_settings", "暂不支持分组聚合。");
        if (data.get("output_type") != null && !"string".equals(String.valueOf(data.get("output_type"))))
            throw new DslException(path + ".output_type", "暂仅支持 string 聚合输出。");
        List<Map<String, Object>> variables = new ArrayList<>();
        for (Object raw : array(data.get("variables"), path + ".variables")) {
            String itemPath = path + ".variables[" + variables.size() + "]";
            variables.add(map("selector", selector(raw, itemPath)));
        }
        if (variables.isEmpty()) throw new DslException(path + ".variables", "至少配置一个聚合变量。");
        return map("variables", variables);
    }

    private Map<String, Object> importListOperator(Map<String, Object> data, String path) {
        allowed(data, Set.of("desc", "title", "type", "selected", "variable", "extract_by", "order_by", "limit"), path);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("inputSelector", selector(data.get("variable"), path + ".variable"));
        config.put("filterOperator", "none");
        config.put("filterKey", "");
        config.put("filterValue", "");
        config.put("sortEnabled", false);
        config.put("sortKey", "");
        config.put("sortOrder", "asc");
        config.put("extractSerial", 0);
        config.put("limit", 0);
        if (data.get("extract_by") != null) {
            Map<String, Object> extract = object(data.get("extract_by"), path + ".extract_by");
            allowed(extract, Set.of("enabled", "serial"), path + ".extract_by");
            if (Boolean.TRUE.equals(extract.get("enabled"))) {
                if (!(extract.get("serial") instanceof Number serial) || serial.intValue() < 1)
                    throw new DslException(path + ".extract_by.serial", "serial 必须为不小于 1 的整数。");
                config.put("extractSerial", serial.intValue());
            }
        }
        if (data.get("order_by") != null) {
            Map<String, Object> order = object(data.get("order_by"), path + ".order_by");
            allowed(order, Set.of("enabled", "value", "var"), path + ".order_by");
            if (Boolean.TRUE.equals(order.get("enabled"))) {
                String direction = order.get("value") == null ? "asc" : String.valueOf(order.get("value"));
                if (!Set.of("asc", "desc").contains(direction))
                    throw new DslException(path + ".order_by.value", "仅支持 asc/desc。");
                config.put("sortEnabled", true);
                config.put("sortOrder", direction);
                config.put("sortKey", order.get("var") == null ? "" : String.valueOf(order.get("var")));
            }
        }
        if (data.get("limit") != null) {
            Map<String, Object> limit = object(data.get("limit"), path + ".limit");
            allowed(limit, Set.of("enabled", "size"), path + ".limit");
            if (Boolean.TRUE.equals(limit.get("enabled"))) {
                if (!(limit.get("size") instanceof Number size) || size.intValue() < 1)
                    throw new DslException(path + ".limit.size", "size 必须为不小于 1 的整数。");
                config.put("limit", size.intValue());
            }
        }
        return config;
    }

    private GraphEdge importEdge(Map<String, Object> raw, String path, Map<String, String> types) {
        allowed(raw, EDGE_FIELDS, path);
        if (raw.get("data") instanceof Map<?, ?> metadata) {
            allowed(metadata, Set.of("isInIteration", "isInLoop", "sourceType", "targetType"), path + ".data");
        }
        String source = requiredString(raw.get("source"), path + ".source");
        String handle = raw.get("sourceHandle") == null ? null : String.valueOf(raw.get("sourceHandle"));
        if ("if-else".equals(types.get(source))) {
            if ("false".equals(handle)) handle = "else";
        } else handle = null;
        return new GraphEdge(requiredString(raw.get("id"), path + ".id"), source,
                requiredString(raw.get("target"), path + ".target"), handle, null);
    }

    private Map<String, Object> exportNode(GraphNode node) {
        Map<String, Object> data = map("desc", "", "selected", false, "title", node.title(), "type", difyType(node.type()));
        switch (node.type()) {
            case "user-input" -> data.put("variables", list(node.config().get("fields"), "fields").stream().map(raw -> {
                Map<?, ?> field = (Map<?, ?>) raw;
                Map<String, Object> result = map("variable", field.get("variable"), "label", field.get("label"),
                        "type", switch (String.valueOf(field.get("type"))) { case "text" -> "text-input"; case "boolean" -> "checkbox"; default -> String.valueOf(field.get("type")); },
                        "required", field.containsKey("required") ? field.get("required") : false);
                if (field.containsKey("default")) result.put("default", field.get("default"));
                if (field.containsKey("options")) result.put("options", field.get("options"));
                return result;
            }).toList());
            case "output" -> data.put("outputs", list(node.config().get("mappings"), "mappings").stream().map(raw -> {
                Map<?, ?> mapping = (Map<?, ?>) raw;
                return map("variable", mapping.get("name"), "value_selector", selectorArray(String.valueOf(mapping.get("selector"))));
            }).toList());
            case "llm" -> exportLlm(node, data);
            case "if-else" -> data.put("cases", list(node.config().get("cases"), "cases").stream().map(raw -> exportCase((Map<?, ?>) raw)).toList());
            case "code" -> exportCode(node, data);
            case "answer" -> data.put("answer", toDifyTemplate(String.valueOf(node.config().get("answer"))));
            case "template" -> exportTemplate(node, data);
            case "variable-aggregator" -> exportAggregator(node, data);
            case "list-operator" -> exportListOperator(node, data);
            case "iteration" -> exportIteration(node, data);
            case "loop" -> exportLoop(node, data);
            case "exit-loop" -> exportExitLoop(node, data);
            case "human-input" -> exportHumanInput(node, data);
            case "agent" -> exportAgent(node, data);
            default -> throw new DslException("$.nodes[id=" + node.id() + "].type", "当前 DSL 不支持导出 " + node.type() + "。");
        }
        Map<String, Object> result = map("id", node.id(), "type", "custom",
                "position", map("x", node.position().x(), "y", node.position().y()),
                "positionAbsolute", map("x", node.position().x(), "y", node.position().y()), "data", data,
                "width", 244, "height", 100, "sourcePosition", "right", "targetPosition", "left", "selected", false);
        if (node.parentId() != null) result.put("parentId", node.parentId());
        return result;
    }

    private void exportIteration(GraphNode node, Map<String, Object> data) {
        data.put("iterator_selector", selectorArray(text(node.config().get("inputSelector"))));
        String outputSelector = text(node.config().get("outputSelector"));
        data.put("output_selector", outputSelector.isBlank() ? List.of() : selectorArray(outputSelector));
        boolean parallel = Boolean.TRUE.equals(node.config().get("parallel"));
        data.put("parallel", parallel);
        data.put("is_parallel", parallel);
    }

    private void exportLoop(GraphNode node, Map<String, Object> data) {
        data.put("loop_count", number(node.config().get("maxIterations")));
        data.put("break_conditions", exportConditions(list(node.config().getOrDefault("breakConditions", List.of()), "breakConditions")));
        data.put("logical_operator", node.config().getOrDefault("breakLogicalOperator", "and"));
        String outputSelector = text(node.config().get("outputSelector"));
        data.put("output_selector", outputSelector.isBlank() ? List.of() : selectorArray(outputSelector));
    }

    private void exportExitLoop(GraphNode node, Map<String, Object> data) {
        data.put("conditions", exportConditions(list(node.config().getOrDefault("conditions", List.of()), "conditions")));
        data.put("logical_operator", node.config().getOrDefault("logicalOperator", "and"));
    }

    private void exportHumanInput(GraphNode node, Map<String, Object> data) {
        data.put("form_content", toDifyTemplate(requiredString(node.config().get("prompt"), "$.config.prompt")));
        data.put("inputs", list(node.config().get("fields"), "fields").stream().map(raw -> {
            Map<?, ?> field = (Map<?, ?>) raw;
            String variable = requiredString(field.get("variable"), "$.config.fields.variable");
            String type = switch (String.valueOf(field.get("type"))) {
                case "text" -> "text-input";
                case "paragraph", "number", "select", "json" -> String.valueOf(field.get("type"));
                case "boolean" -> "checkbox";
                default -> throw new DslException("$.nodes[id=" + node.id() + "].config.fields.type",
                        "不支持的 Human Input 类型 " + field.get("type") + "。");
            };
            if (!variable.equals(String.valueOf(field.get("label"))))
                throw new DslException("$.nodes[id=" + node.id() + "].config.fields.label",
                        "Dify Human Input 不支持独立 label，导出时 label 必须与变量名一致。");
            if (!Boolean.TRUE.equals(field.get("required")))
                throw new DslException("$.nodes[id=" + node.id() + "].config.fields.required",
                        "Dify Human Input 字段均为必填，不能导出可选字段。");
            Map<String, Object> input = map("output_variable_name", variable, "type", type);
            if (field.get("default") != null) {
                if (!Set.of("text-input", "paragraph").contains(type) || !(field.get("default") instanceof String value))
                    throw new DslException("$.nodes[id=" + node.id() + "].config.fields.default",
                            "当前仅支持文本与段落字段的字符串常量默认值。");
                input.put("default", map("type", "constant", "selector", List.of(), "value", value));
            }
            if ("select".equals(type)) {
                input.put("option_source", map("type", "constant", "selector", List.of(),
                        "value", list(field.get("options"), "fields.options")));
            } else if (field.containsKey("options")) {
                throw new DslException("$.nodes[id=" + node.id() + "].config.fields.options",
                        "仅 select 字段支持 options。");
            }
            return input;
        }).toList());
        data.put("user_actions", List.of());
        data.put("timeout", 36);
        data.put("timeout_unit", "hour");
    }

    private List<Map<String, Object>> exportConditions(List<?> conditions) {
        List<Map<String, Object>> exported = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            Map<?, ?> condition = (Map<?, ?>) conditions.get(i);
            Object value = condition.get("value");
            exported.add(map("id", "condition-" + (i + 1), "comparison_operator", condition.get("operator"), "value", value,
                    "varType", value instanceof Number ? "number" : value instanceof Boolean ? "boolean" : "string",
                    "variable_selector", selectorArray(text(condition.get("variable")))));
        }
        return exported;
    }

    private void exportLlm(GraphNode node, Map<String, Object> data) {
        Map<String, Object> params = new LinkedHashMap<>();
        copy(node.config(), "temperature", params, "temperature");
        copy(node.config(), "topP", params, "top_p");
        copy(node.config(), "maxTokens", params, "max_tokens");
        copy(node.config(), "stop", params, "stop");
        copy(node.config(), "responseFormat", params, "response_format");
        data.put("model", map("name", node.config().get("model"), "provider", "langgenius/openai/openai", "mode", "chat", "completion_params", params));
        data.put("prompt_template", list(node.config().get("messages"), "messages").stream().map(raw -> {
            Map<?, ?> message = (Map<?, ?>) raw;
            return map("role", message.get("role"), "text", toDifyTemplate(String.valueOf(message.get("content"))));
        }).toList());
    }

    private void exportAgent(GraphNode node, Map<String, Object> data) {
        Map<String, Object> config = node.config();
        String strategy = String.valueOf(config.getOrDefault("strategy", "function_call"));
        if (!"function_call".equals(strategy))
            throw new DslException("$.nodes[id=" + node.id() + "].config.strategy", "Dify DSL 仅支持 function_call 策略。");
        data.put("strategy", "function_call");
        data.put("instruction", toDifyTemplate(requiredString(config.get("instruction"), "$.config.instruction")));
        Map<String, Object> params = new LinkedHashMap<>();
        copy(config, "temperature", params, "temperature");
        copy(config, "topP", params, "top_p");
        copy(config, "maxTokens", params, "max_tokens");
        data.put("model", map("name", config.get("model"), "provider", "langgenius/openai/openai",
                "mode", "chat", "completion_params", params));
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object raw : list(config.getOrDefault("tools", List.of()), "tools")) {
            String name = String.valueOf(raw);
            if (!"calculator".equals(name))
                throw new DslException("$.nodes[id=" + node.id() + "].config.tools",
                        "Dify DSL 导出仅支持内置 calculator 工具，不支持 " + name + "。");
            tools.add(map("provider_id", "builtin", "provider_type", "builtin",
                    "tool_name", "calculator", "tool_label", "Calculator", "type", "builtin"));
        }
        data.put("tools", tools);
        data.put("vision", map("enabled", false));
    }

    private Map<String, Object> exportCase(Map<?, ?> one) {
        List<?> conditions = list(one.get("conditions"), "conditions");
        List<Map<String, Object>> exported = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            Map<?, ?> condition = (Map<?, ?>) conditions.get(i);
            Object value = condition.get("value");
            exported.add(map("id", "condition-" + (i + 1), "comparison_operator", condition.get("operator"), "value", value,
                    "varType", value instanceof Number ? "number" : value instanceof Boolean ? "boolean" : "string",
                    "variable_selector", selectorArray(String.valueOf(condition.get("variable")))));
        }
        return map("case_id", one.get("id"), "logical_operator", one.containsKey("logicalOperator") ? one.get("logicalOperator") : "and", "conditions", exported);
    }

    private void exportCode(GraphNode node, Map<String, Object> data) {
        data.put("code_language", node.config().get("language"));
        data.put("code", node.config().get("code"));
        data.put("variables", list(node.config().getOrDefault("inputs", List.of()), "inputs").stream().map(raw -> {
            Map<?, ?> input = (Map<?, ?>) raw;
            return map("variable", input.get("name"), "value_selector", selectorArray(String.valueOf(input.get("selector"))));
        }).toList());
        Map<String, Object> outputs = new LinkedHashMap<>();
        object(node.config().get("outputs"), "outputs").forEach((name, raw) -> {
            Map<?, ?> declaration = (Map<?, ?>) raw;
            outputs.put(name, map("type", declaration.get("type"), "children", null));
        });
        data.put("outputs", outputs);
    }

    private void exportTemplate(GraphNode node, Map<String, Object> data) {
        data.put("template", toDifyTemplate(String.valueOf(node.config().get("template"))));
        data.put("variables", list(node.config().getOrDefault("variables", List.of()), "variables").stream().map(raw -> {
            Map<?, ?> variable = (Map<?, ?>) raw;
            return map("variable", variable.get("variable"), "value_selector", selectorArray(String.valueOf(variable.get("selector"))));
        }).toList());
    }

    private void exportAggregator(GraphNode node, Map<String, Object> data) {
        data.put("output_type", "string");
        data.put("variables", list(node.config().get("variables"), "variables").stream()
                .map(raw -> selectorArray(String.valueOf(raw instanceof Map<?, ?> item ? item.get("selector") : raw))).toList());
    }

    private void exportListOperator(GraphNode node, Map<String, Object> data) {
        Map<String, Object> config = node.config();
        String operator = String.valueOf(config.getOrDefault("filterOperator", "none"));
        if (!"none".equals(operator))
            throw new DslException("$.nodes[id=" + node.id() + "].config.filterOperator",
                    "通用过滤条件暂不支持导出为 Dify DSL。");
        data.put("variable", selectorArray(String.valueOf(config.get("inputSelector"))));
        int serial = number(config.get("extractSerial"));
        data.put("extract_by", map("enabled", serial > 0, "serial", serial));
        boolean sort = Boolean.TRUE.equals(config.get("sortEnabled"));
        data.put("order_by", map("enabled", sort, "value", config.getOrDefault("sortOrder", "asc"),
                "var", config.getOrDefault("sortKey", "")));
        int limit = number(config.get("limit"));
        data.put("limit", map("enabled", limit > 0, "size", limit));
    }

    private Map<String, Object> exportEdge(GraphEdge edge, Map<String, String> types, Map<String, String> parents) {
        String handle = "if-else".equals(types.get(edge.source())) ? ("else".equals(edge.sourceHandle()) ? "false" : edge.sourceHandle()) : "source";
        String sourceParent = types.get(parents.get(edge.source()));
        String targetParent = types.get(parents.get(edge.target()));
        return map("id", edge.id(), "type", "custom", "source", edge.source(), "sourceHandle", handle,
                "target", edge.target(), "targetHandle", "target", "zIndex", 0,
                "data", map("isInIteration", "iteration".equals(sourceParent) || "iteration".equals(targetParent),
                        "isInLoop", "loop".equals(sourceParent) || "loop".equals(targetParent),
                        "sourceType", difyType(types.get(edge.source())), "targetType", difyType(types.get(edge.target()))));
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static int number(Object raw) {
        return raw instanceof Number value ? value.intValue() : 0;
    }

    private static String difyType(String type) {
        return switch (type) {
            case "user-input" -> "start";
            case "output" -> "end";
            case "template" -> "template-transform";
            default -> type;
        };
    }

    private static Position position(Object raw, String path) {
        Map<String, Object> value = object(raw, path);
        allowed(value, Set.of("x", "y"), path);
        if (!(value.get("x") instanceof Number x) || !(value.get("y") instanceof Number y)) throw new DslException(path, "x/y 必须为数值。");
        return new Position(x.doubleValue(), y.doubleValue());
    }

    private static String selector(Object raw, String path) {
        List<?> value = array(raw, path);
        if (value.size() < 2) throw new DslException(path, "选择器至少包含 nodeId 与 key。");
        return value.stream().map(String::valueOf).reduce((a, b) -> a + "." + b).orElseThrow();
    }

    private static String optionalSelector(Object raw, String path) {
        if (raw == null) return "";
        List<?> value = array(raw, path);
        return value.isEmpty() ? "" : selector(raw, path);
    }

    private static List<String> selectorArray(String selector) {
        String normalized = selector.replaceAll("^\\{\\{\\s*|\\s*}}$", "");
        return List.of(normalized.split("\\."));
    }

    private static String fromDifyTemplate(String value) {
        Matcher matcher = DIFY_TEMPLATE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, "{{" + matcher.group(1) + "." + matcher.group(2) + "}}");
        matcher.appendTail(result);
        return result.toString();
    }

    private static String toDifyTemplate(String value) {
        Matcher matcher = GRAPH_TEMPLATE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, "{{#" + matcher.group(1) + "." + matcher.group(2) + "#}}");
        matcher.appendTail(result);
        return result.toString();
    }

    private static String logical(Object raw, String path) {
        if (raw == null) return "and";
        String value = String.valueOf(raw);
        if (!Set.of("and", "or").contains(value)) throw new DslException(path, "仅支持 and/or。");
        return value;
    }

    private static String operator(String raw) {
        return switch (raw) {
            case "is", "=" -> "equals";
            case "is not", "≠" -> "not-equals";
            case "contains" -> "contains";
            case "not contains" -> "not-contains";
            case "start with", "starts with" -> "starts-with";
            case "end with", "ends with" -> "ends-with";
            case "empty" -> "is-empty";
            case "not empty" -> "not-empty";
            case ">" -> "greater";
            case ">=", "≥" -> "greater-or-equal";
            case "<" -> "less";
            case "<=", "≤" -> "less-or-equal";
            default -> raw;
        };
    }

    private static void empty(Object value, String path) {
        if (value == null) return;
        if (value instanceof Map<?, ?> map && map.isEmpty()) return;
        if (value instanceof List<?> list && list.isEmpty()) return;
        throw new DslException(path, "第一期仅支持空配置，不能静默丢弃该字段。");
    }

    private static void allowed(Map<?, ?> value, Set<String> allowed, String path) {
        for (Object key : value.keySet()) if (!allowed.contains(String.valueOf(key)))
            throw new DslException(path + "." + key, "不支持的字段，未执行导入。");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, String path) {
        if (raw instanceof Map<?, ?>) return (Map<String, Object>) raw;
        throw new DslException(path, "必须为对象。");
    }

    private static List<?> array(Object raw, String path) {
        if (raw instanceof List<?> list) return list;
        throw new DslException(path, "必须为数组。");
    }

    private static List<?> list(Object raw, String name) {
        if (raw instanceof List<?> list) return list;
        throw new DslException("$.config." + name, "必须为数组。");
    }

    private static String requiredString(Object raw, String path) {
        if (raw instanceof String value && !value.isBlank()) return value;
        throw new DslException(path, "必填字符串缺失。");
    }

    private static void copy(Map<String, Object> from, String fromKey, Map<String, Object> to, String toKey) {
        if (from.containsKey(fromKey)) to.put(toKey, from.get(fromKey));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
