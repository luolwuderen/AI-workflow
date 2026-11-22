package com.acme.workflow.infra.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 向量以 JSON 数组存于 TEXT 列，避免依赖数据库向量扩展。 */
@Component
public class VectorCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public String encode(float[] vector) {
        try {
            return mapper.writeValueAsString(vector);
        } catch (Exception error) {
            throw new IllegalStateException("向量序列化失败。", error);
        }
    }

    public float[] decode(String json) {
        try {
            return mapper.readValue(json, float[].class);
        } catch (Exception error) {
            throw new IllegalStateException("向量反序列化失败。", error);
        }
    }
}
