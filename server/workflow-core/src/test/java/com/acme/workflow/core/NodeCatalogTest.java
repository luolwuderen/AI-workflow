package com.acme.workflow.core;

import com.acme.workflow.core.catalog.NodeCatalog;
import com.acme.workflow.core.model.NodeCatalogEntry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NodeCatalogTest {
    @Test
    void exposesExactlyThePhaseOneCatalogAndExecutableBoundary() {
        NodeCatalog catalog = new NodeCatalog();

        assertThat(catalog.all()).hasSize(24);
        assertThat(catalog.all()).allSatisfy(entry -> {
            assertThat(entry.configSchema()).isNotNull();
            assertThat(entry.uiSchema()).isNotNull();
            assertThat(entry.supportMessage()).isNotBlank();
        });
        Set<String> executable = catalog.all().stream().filter(NodeCatalogEntry::executable)
                .map(NodeCatalogEntry::type).collect(Collectors.toSet());
        assertThat(executable).containsExactlyInAnyOrder("user-input", "llm", "answer", "output", "if-else", "code",
                "template", "variable-aggregator", "variable-assigner", "list-operator", "http-request",
                "iteration", "loop", "exit-loop", "human-input", "agent");
    }
}
