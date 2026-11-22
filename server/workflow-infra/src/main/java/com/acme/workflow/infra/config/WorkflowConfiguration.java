package com.acme.workflow.infra.config;

import com.acme.workflow.core.catalog.NodeCatalog;
import com.acme.workflow.core.validation.GraphValidator;
import com.acme.workflow.runtime.HumanInputGateway;
import com.acme.workflow.runtime.NodeExecutor;
import com.acme.workflow.runtime.WorkflowExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class WorkflowConfiguration {
    @Bean
    public NodeCatalog nodeCatalog() { return new NodeCatalog(); }

    @Bean
    public GraphValidator graphValidator(NodeCatalog catalog) { return new GraphValidator(catalog); }

    @Bean
    public WorkflowExecutor workflowExecutor(List<NodeExecutor> executors, NodeCatalog catalog,
                                             HumanInputGateway humanInputs) {
        return new WorkflowExecutor(executors, catalog, humanInputs);
    }
}
