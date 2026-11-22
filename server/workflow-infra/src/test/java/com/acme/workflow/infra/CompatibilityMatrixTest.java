package com.acme.workflow.infra;

import com.acme.workflow.core.catalog.NodeCatalog;
import com.acme.workflow.core.model.AppType;
import com.acme.workflow.core.model.GraphDocument;
import com.acme.workflow.core.model.GraphNode;
import com.acme.workflow.core.model.NodeCatalogEntry;
import com.acme.workflow.core.model.Position;
import com.acme.workflow.infra.dify.DifyDslAdapter;
import com.acme.workflow.infra.dify.DslException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力矩阵与实现的一致性契约：matrix.json 中每个节点的 executable / dslRoundTrip
 * 必须与 NodeCatalog 及 DifyDslAdapter 的真实行为一致，避免文档与代码漂移。
 */
class CompatibilityMatrixTest {
    private static final Path MATRIX = Path.of("../../compatibility/matrix.json");

    @Test
    void matrixMatchesCatalogAndDslCapabilities() throws Exception {
        Map<String, Map<String, Object>> rows = readMatrix();
        NodeCatalog catalog = new NodeCatalog();
        Set<String> catalogTypes = catalog.all().stream().map(NodeCatalogEntry::type).collect(Collectors.toSet());

        assertThat(rows.keySet()).containsExactlyInAnyOrderElementsOf(catalogTypes);

        DifyDslAdapter adapter = new DifyDslAdapter();
        for (NodeCatalogEntry entry : catalog.all()) {
            Map<String, Object> row = rows.get(entry.type());
            String label = entry.type();
            assertThat(row.get("catalog")).as(label + ".catalog").isEqualTo(true);
            assertThat(row.get("configurable")).as(label + ".configurable").isEqualTo(true);
            assertThat(row.get("validatable")).as(label + ".validatable").isEqualTo(true);
            assertThat(row.get("executable")).as(label + ".executable").isEqualTo(entry.executable());

            boolean dslExportable = exportable(adapter, entry);
            assertThat(row.get("dslRoundTrip")).as(label + ".dslRoundTrip").isEqualTo(dslExportable);
            if (dslExportable) {
                assertThat(entry.executable()).as(label + ".dslRoundTrip 要求节点可执行").isTrue();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> readMatrix() throws Exception {
        Map<String, Object> root = new ObjectMapper().readValue(Files.readString(MATRIX), Map.class);
        List<Map<String, Object>> capabilities = (List<Map<String, Object>>) root.get("capabilities");
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> capability : capabilities) {
            rows.put(String.valueOf(capability.get("type")), capability);
        }
        return rows;
    }

    /**
     * 仅以「导出器是否声明支持该节点类型」作为 DSL 能力判据：
     * 部分节点（如 human-input）的默认配置不足以通过导出期校验，但类型本身是支持的。
     */
    private boolean exportable(DifyDslAdapter adapter, NodeCatalogEntry entry) {
        GraphNode node = new GraphNode("node", entry.type(), entry.title(), new Position(0, 0),
                new LinkedHashMap<>(entry.defaults()), null);
        GraphDocument graph = new GraphDocument(1, AppType.WORKFLOW, List.of(node), List.of(),
                Map.of("x", 0, "y", 0, "zoom", 1));
        try {
            adapter.exportYaml(graph, "Compatibility");
            return true;
        } catch (DslException error) {
            return !String.valueOf(error.getMessage()).contains("不支持导出");
        }
    }
}
