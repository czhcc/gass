package com.trs.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON persistence codec, separate from Spring's HTTP mapper. @author agent */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    /** Serializes a persistence value. @author agent */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON value", e);
        }
    }

    /** Reads a typed persisted value. @author agent */
    public static <T> T read(String value, Class<T> type) {
        try {
            return MAPPER.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON document", e);
        }
    }
}
