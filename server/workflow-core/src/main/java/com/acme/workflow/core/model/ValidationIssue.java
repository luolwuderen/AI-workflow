package com.acme.workflow.core.model;

public record ValidationIssue(String path, String code, String message) {
}
