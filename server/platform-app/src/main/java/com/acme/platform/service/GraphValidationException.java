package com.acme.platform.service;

import com.acme.workflow.core.model.ValidationResult;

public class GraphValidationException extends RuntimeException {
    private final ValidationResult result;

    public GraphValidationException(ValidationResult result) {
        super("图校验失败，共 " + result.issues().size() + " 个问题。");
        this.result = result;
    }

    public ValidationResult result() { return result; }
}
