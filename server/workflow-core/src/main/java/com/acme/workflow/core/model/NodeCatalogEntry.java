package com.acme.workflow.core.model;

import java.util.List;
import java.util.Map;

public record NodeCatalogEntry(String type, String title, String description, String category,
                               List<AppType> appTypes, boolean executable, String supportMessage,
                               Map<String, Object> configSchema, Map<String, Object> uiSchema,
                               Map<String, Object> defaults, Map<String, Object> outputs) {
}
