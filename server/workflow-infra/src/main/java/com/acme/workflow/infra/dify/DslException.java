package com.acme.workflow.infra.dify;

public class DslException extends IllegalArgumentException {
    private final String path;

    public DslException(String path, String message) {
        super(path + ": " + message);
        this.path = path;
    }

    public String path() { return path; }
}
