package com.acme.platform.service;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}
