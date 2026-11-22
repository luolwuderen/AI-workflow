package com.acme.platform.api;

import java.time.Instant;
import java.util.Map;

public record EventView(long sequence, String type, String nodeId, Map<String, Object> data, Instant createdAt) {
}
