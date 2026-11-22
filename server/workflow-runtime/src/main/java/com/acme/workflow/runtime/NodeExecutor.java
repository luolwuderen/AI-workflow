package com.acme.workflow.runtime;

import java.util.Map;

public interface NodeExecutor {
    String type();
    Map<String, Object> execute(NodeExecutionContext context);
}
