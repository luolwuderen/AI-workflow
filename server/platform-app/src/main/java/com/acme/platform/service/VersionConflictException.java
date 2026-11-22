package com.acme.platform.service;

public class VersionConflictException extends RuntimeException {
    public VersionConflictException(long expected, long actual) {
        super("草稿版本冲突：expectedVersion=" + expected + "，当前版本=" + actual + "。");
    }
}
