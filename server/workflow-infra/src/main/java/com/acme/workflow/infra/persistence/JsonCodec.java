package com.acme.workflow.infra.persistence;

import com.acme.workflow.core.model.GraphDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class JsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("JSON 序列化失败。", error); }
    }

    public GraphDocument graph(String json) {
        try { return mapper.readValue(json, GraphDocument.class); }
        catch (Exception error) { throw new IllegalStateException("固定图快照反序列化失败。", error); }
    }

    public Map<String, Object> map(String json) {
        if (json == null) return Map.of();
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception error) { throw new IllegalStateException("JSON 反序列化失败。", error); }
    }
}
