package io.genesis.sandbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;

/** Internal, shared and thread-safe JSON codec. */
final class JsonCodec {
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private JsonCodec() {}

    static byte[] write(Object value) throws IOException {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException error) {
            throw new IOException("failed to encode sandbox request", error);
        }
    }

    static <T> T read(byte[] value, Class<T> type) throws IOException {
        try {
            return MAPPER.readValue(value, type);
        } catch (JsonMappingException error) {
            throw new IOException("sandbox response does not match " + type.getSimpleName(), error);
        }
    }

    static <T> T read(byte[] value, TypeReference<T> type) throws IOException {
        try {
            return MAPPER.readValue(value, type);
        } catch (JsonMappingException error) {
            throw new IOException("sandbox response has an unexpected JSON shape", error);
        }
    }

    static <T> T readLenient(byte[] value, Class<T> type) {
        if (value == null || value.length == 0) return null;
        try {
            return MAPPER.readValue(value, type);
        } catch (IOException ignored) {
            return null;
        }
    }
}
