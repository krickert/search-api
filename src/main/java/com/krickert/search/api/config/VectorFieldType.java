package com.krickert.search.api.config;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum VectorFieldType {
    INLINE,
    EMBEDDED_DOC,
    CHILD_COLLECTION
}