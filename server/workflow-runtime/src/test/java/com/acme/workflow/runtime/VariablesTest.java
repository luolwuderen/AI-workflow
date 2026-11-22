package com.acme.workflow.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariablesTest {
    @Test
    void selectsNestedValuesAndRendersReplacementCharactersLiterally() {
        Map<String, Map<String, Object>> outputs = Map.of("node", Map.of(
                "items", List.of(Map.of("name", "first")), "text", "$1\\value"));

        assertThat(Variables.select("node.items.0.name", outputs)).isEqualTo("first");
        assertThat(Variables.select("{{ node.items.0.name }}", outputs)).isEqualTo("first");
        assertThat(Variables.render("value={{node.text}}", outputs)).isEqualTo("value=$1\\value");
    }

    @Test
    void rejectsUnknownOrInvalidSelectors() {
        assertThatThrownBy(() -> Variables.select("missing.text", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("尚未执行或不存在");
        assertThatThrownBy(() -> Variables.select("not-a-selector", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nodeId.key");
    }
}
