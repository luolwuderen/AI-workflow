package com.acme.workflow.runtime;

import java.util.Map;

public record RunResult(RunStatus status, Map<String, Object> outputs, String error) {
    public RunResult {
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }
}
