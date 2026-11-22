package com.acme.workflow.infra;

import com.acme.workflow.core.catalog.NodeCatalog;
import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.Position;
import com.acme.workflow.core.validation.GraphValidator;
import com.acme.workflow.infra.dify.DifyDslAdapter;
import com.acme.workflow.infra.dify.DslException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DifyDslAdapterTest {
    private static final Path GOLDEN = Path.of("../../tests/dsl-golden");
    private final DifyDslAdapter adapter = new DifyDslAdapter();
    private final GraphValidator validator = new GraphValidator(new NodeCatalog());

    @ParameterizedTest
    @ValueSource(strings = {"core-workflow.yml", "core-chatflow.yml", "core-conditions.yml", "data-nodes.yml",
            "list-operator.yml", "iteration-loop.yml"})
    void goldenDslRoundTripsWithoutSemanticLoss(String file) throws IOException {
        GraphDocument imported = adapter.importYaml(Files.readString(GOLDEN.resolve(file)));

        assertThat(validator.validate(imported).issues()).isEmpty();
        String exported = adapter.exportYaml(imported, "Round Trip");
        GraphDocument roundTripped = adapter.importYaml(exported);
        assertThat(roundTripped).isEqualTo(imported);
        assertThat(validator.validate(roundTripped).issues()).isEmpty();
    }

    @Test
    void rejectsDependenciesAtTheirPrecisePath() throws IOException {
        String yaml = Files.readString(GOLDEN.resolve("core-chatflow.yml"))
                .replace("kind: app\n", "kind: app\ndependencies: []\n");

        assertThatThrownBy(() -> adapter.importYaml(yaml))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.dependencies")
                .hasMessageContaining("不支持 dependencies");
    }

    @Test
    void rejectsUnknownFieldsAndUnsupportedNodesInsteadOfDroppingThem() throws IOException {
        String original = Files.readString(GOLDEN.resolve("core-chatflow.yml"));
        String unknown = original.replace("kind: app\n", "kind: app\nmystery: true\n");
        assertThatThrownBy(() -> adapter.importYaml(unknown))
                .isInstanceOf(DslException.class).hasMessageContaining("$.mystery");

        String unsupported = original.replace("type: start", "type: schedule");
        assertThatThrownBy(() -> adapter.importYaml(unsupported))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.workflow.graph.nodes[0].data.type")
                .hasMessageContaining("schedule");
    }

    @Test
    void humanInputSupportedSubsetRoundTripsWithoutSemanticLoss() {
        GraphDocument imported = adapter.importYaml(HUMAN_INPUT_DSL);

        assertThat(validator.validate(imported).issues()).isEmpty();
        assertThat(imported.nodes().get(1).type()).isEqualTo("human-input");
        assertThat(imported.nodes().get(1).config().get("prompt")).isEqualTo("处理 {{start.text}}");
        GraphDocument roundTripped = adapter.importYaml(adapter.exportYaml(imported, "Human Input"));
        assertThat(roundTripped).isEqualTo(imported);
        assertThat(validator.validate(roundTripped).issues()).isEmpty();
    }

    @Test
    void rejectsHumanInputSemanticsThatCannotBePreserved() {
        String fileInput = HUMAN_INPUT_DSL.replace("type: paragraph", "type: file");
        assertThatThrownBy(() -> adapter.importYaml(fileInput))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.workflow.graph.nodes[1].data.inputs[0].type")
                .hasMessageContaining("file");

        String actions = HUMAN_INPUT_DSL.replace("user_actions: []",
                "user_actions:\n              - id: approve\n                title: Approve");
        assertThatThrownBy(() -> adapter.importYaml(actions))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.workflow.graph.nodes[1].data.user_actions")
                .hasMessageContaining("动作分支");

        String timeout = HUMAN_INPUT_DSL.replace("timeout: 36", "timeout: 1");
        assertThatThrownBy(() -> adapter.importYaml(timeout))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.workflow.graph.nodes[1].data.timeout")
                .hasMessageContaining("36 小时");
    }

    @Test
    void agentSupportedSubsetRoundTripsWithoutSemanticLoss() {
        GraphDocument imported = adapter.importYaml(AGENT_DSL);

        assertThat(validator.validate(imported).issues()).isEmpty();
        assertThat(imported.nodes().get(1).type()).isEqualTo("agent");
        assertThat(imported.nodes().get(1).config().get("instruction")).isEqualTo("Compute {{start.text}}");
        assertThat(imported.nodes().get(1).config().get("tools")).isEqualTo(List.of("calculator"));
        assertThat(imported.nodes().get(1).config().get("strategy")).isEqualTo("function_call");
        GraphDocument roundTripped = adapter.importYaml(adapter.exportYaml(imported, "Agent"));
        assertThat(roundTripped).isEqualTo(imported);
        assertThat(validator.validate(roundTripped).issues()).isEmpty();
    }

    @Test
    void agentCodeToolRoundTripsAsBuiltinExtension() {
        String dsl = AGENT_DSL.replace("tool_name: calculator", "tool_name: code")
                .replace("tool_label: Calculator", "tool_label: Code");
        GraphDocument imported = adapter.importYaml(dsl);

        assertThat(imported.nodes().get(1).config().get("tools")).isEqualTo(List.of("code"));
        assertThat(validator.validate(imported).issues()).isEmpty();
        GraphDocument roundTripped = adapter.importYaml(adapter.exportYaml(imported, "Agent"));
        assertThat(roundTripped).isEqualTo(imported);
    }

    @Test
    void rejectsAgentSemanticsThatCannotBePreserved() {
        String react = AGENT_DSL.replace("strategy: function_call", "strategy: ReAct");
        assertThatThrownBy(() -> adapter.importYaml(react))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.workflow.graph.nodes[1].data.strategy")
                .hasMessageContaining("ReAct");

        String unknownTool = AGENT_DSL.replace("tool_name: calculator", "tool_name: wikipedia");
        assertThatThrownBy(() -> adapter.importYaml(unknownTool))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.workflow.graph.nodes[1].data.tools[0].tool_name")
                .hasMessageContaining("wikipedia");

        String vision = AGENT_DSL.replace("enabled: false", "enabled: true");
        assertThatThrownBy(() -> adapter.importYaml(vision))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.workflow.graph.nodes[1].data.vision");
    }

    private static final String HUMAN_INPUT_DSL = """
            app:
              name: Human Input
              description: ''
              icon: 🤖
              icon_background: '#FFEAD5'
              mode: workflow
              use_icon_as_answer_icon: false
            kind: app
            version: 0.4.0
            workflow:
              conversation_variables: []
              environment_variables: []
              features: {}
              graph:
                nodes:
                  - id: start
                    type: custom
                    position: {x: 80, y: 160}
                    data:
                      desc: ''
                      title: User Input
                      type: start
                      selected: false
                      variables:
                        - variable: text
                          label: Text
                          type: text-input
                          required: true
                  - id: human
                    type: custom
                    position: {x: 360, y: 160}
                    data:
                      desc: ''
                      title: Approval
                      type: human-input
                      selected: false
                      form_content: '处理 {{#start.text#}}'
                      inputs:
                        - output_variable_name: note
                          type: paragraph
                          default: {type: constant, selector: [], value: ok}
                        - output_variable_name: decision
                          type: select
                          option_source: {type: constant, selector: [], value: [approve, reject]}
                        - output_variable_name: count
                          type: number
                        - output_variable_name: approved
                          type: checkbox
                        - output_variable_name: payload
                          type: json
                        - output_variable_name: short
                          type: text-input
                      user_actions: []
                      timeout: 36
                      timeout_unit: hour
                  - id: end
                    type: custom
                    position: {x: 640, y: 160}
                    data:
                      desc: ''
                      title: Output
                      type: end
                      selected: false
                      outputs:
                        - variable: note
                          value_selector: [human, note]
                edges:
                  - id: edge-start-human
                    type: custom
                    source: start
                    sourceHandle: source
                    target: human
                    targetHandle: target
                  - id: edge-human-end
                    type: custom
                    source: human
                    sourceHandle: source
                    target: end
                    targetHandle: target
                viewport: {x: 0, y: 0, zoom: 1}
            """;

    private static final String AGENT_DSL = """
            app:
              name: Agent
              description: ''
              icon: 🤖
              icon_background: '#FFEAD5'
              mode: workflow
              use_icon_as_answer_icon: false
            kind: app
            version: 0.4.0
            workflow:
              conversation_variables: []
              environment_variables: []
              features: {}
              graph:
                nodes:
                  - id: start
                    type: custom
                    position: {x: 80, y: 160}
                    data:
                      desc: ''
                      title: User Input
                      type: start
                      selected: false
                      variables:
                        - variable: text
                          label: Text
                          type: text-input
                          required: true
                  - id: agent
                    type: custom
                    position: {x: 360, y: 160}
                    data:
                      desc: ''
                      title: Calculator
                      type: agent
                      selected: false
                      strategy: function_call
                      instruction: 'Compute {{#start.text#}}'
                      model:
                        provider: langgenius/openai/openai
                        name: gpt-4o
                        mode: chat
                        completion_params:
                          temperature: 0.5
                      tools:
                        - provider_id: builtin
                          provider_type: builtin
                          tool_name: calculator
                          tool_label: Calculator
                          type: builtin
                      vision:
                        enabled: false
                  - id: end
                    type: custom
                    position: {x: 640, y: 160}
                    data:
                      desc: ''
                      title: Output
                      type: end
                      selected: false
                      outputs:
                        - variable: result
                          value_selector: [agent, text]
                edges:
                  - id: edge-start-agent
                    type: custom
                    source: start
                    sourceHandle: source
                    target: agent
                    targetHandle: target
                  - id: edge-agent-end
                    type: custom
                    source: agent
                    sourceHandle: source
                    target: end
                    targetHandle: target
                viewport: {x: 0, y: 0, zoom: 1}
            """;

    @Test
    void classifierSupportedSubsetRoundTripsWithoutSemanticLoss() {
        GraphDocument imported = adapter.importYaml(CLASSIFIER_DSL);

        assertThat(validator.validate(imported).issues()).isEmpty();
        assertThat(imported.nodes().get(1).type()).isEqualTo("question-classifier");
        assertThat(imported.nodes().get(1).config().get("query")).isEqualTo("{{start.text}}");
        assertThat(imported.nodes().get(1).config().get("instruction")).isEqualTo("只输出类别 id");
        GraphDocument roundTripped = adapter.importYaml(adapter.exportYaml(imported, "Classifier"));
        assertThat(roundTripped).isEqualTo(imported);
        assertThat(validator.validate(roundTripped).issues()).isEmpty();
    }

    @Test
    void rejectsClassifierSemanticsThatCannotBePreserved() {
        String memory = CLASSIFIER_DSL.replace("assistant: ''", "assistant: '你是助手'");
        assertThatThrownBy(() -> adapter.importYaml(memory))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.workflow.graph.nodes[1].data.memory");

        String unknownClassField = CLASSIFIER_DSL.replace("              name: 数学",
                "              name: 数学\n              description: 数学问题");
        assertThatThrownBy(() -> adapter.importYaml(unknownClassField))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.workflow.graph.nodes[1].data.classes[0]")
                .hasMessageContaining("description");

        String vision = CLASSIFIER_DSL.replace("          instruction:",
                "          vision:\n            enabled: true\n          instruction:");
        assertThatThrownBy(() -> adapter.importYaml(vision))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.workflow.graph.nodes[1].data.vision.enabled");
    }

    @Test
    void rejectsClassifierExportWhenQueryIsNotSingleSelector() {
        GraphNode classifier = new GraphNode("classifier", "question-classifier", "Classifier",
                new Position(0, 0), Map.of("model", "gpt-4o-mini", "query", "两个变量 {{start.a}} {{start.b}}",
                        "classes", List.of(Map.of("id", "1", "name", "A"))));
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(classifier), List.of(),
                Map.of("x", 0, "y", 0, "zoom", 1));

        assertThatThrownBy(() -> adapter.exportYaml(graph, "Classifier"))
                .isInstanceOf(DslException.class)
                .hasMessageContaining("$.nodes[id=classifier].config.query");
    }

    private static final String CLASSIFIER_DSL = """
            app:
              name: Classifier
              description: ''
              icon: 🤖
              icon_background: '#FFEAD5'
              mode: workflow
              use_icon_as_answer_icon: false
            kind: app
            version: 0.4.0
            workflow:
              conversation_variables: []
              environment_variables: []
              features: {}
              graph:
                nodes:
                  - id: start
                    type: custom
                    position: {x: 80, y: 160}
                    data:
                      desc: ''
                      title: User Input
                      type: start
                      selected: false
                      variables:
                        - variable: text
                          label: Text
                          type: text-input
                          required: true
                  - id: classifier
                    type: custom
                    position: {x: 360, y: 160}
                    data:
                      desc: ''
                      title: Classifier
                      type: question-classifier
                      selected: false
                      query_variable_selector: [start, text]
                      model:
                        provider: langgenius/openai/openai
                        name: gpt-4o-mini
                        mode: chat
                        completion_params:
                          temperature: 0.2
                          top_p: 1.0
                      classes:
                        - id: '1'
                          name: 数学
                        - id: '2'
                          name: 其他
                      instruction: '只输出类别 id'
                      memory:
                        role_prefix:
                          assistant: ''
                          user: ''
                        query_prompt_template: ''
                  - id: end
                    type: custom
                    position: {x: 640, y: 160}
                    data:
                      desc: ''
                      title: Output
                      type: end
                      selected: false
                      outputs:
                        - variable: branch
                          value_selector: [classifier, branch]
                edges:
                  - id: edge-start-classifier
                    type: custom
                    source: start
                    sourceHandle: source
                    target: classifier
                    targetHandle: target
                  - id: edge-classifier-end
                    type: custom
                    source: classifier
                    sourceHandle: '1'
                    target: end
                    targetHandle: target
                viewport: {x: 0, y: 0, zoom: 1}
            """;
}
