package com.krickert.search.api.config;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Singleton
public class CollectionConfigValidator {
    private static final Logger log = LoggerFactory.getLogger(CollectionConfigValidator.class);
    private final CollectionConfig collectionConfig;

    @Inject
    public CollectionConfigValidator(CollectionConfig collectionConfig) {
        this.collectionConfig = collectionConfig;
    }

    @PostConstruct
    public void validate() {
        Map<String, VectorFieldInfo> vectorFields = collectionConfig.getVectorFieldsByName();
        if (vectorFields == null || vectorFields.isEmpty()) {
            log.error("No vector fields configured for the collection.");
            throw new IllegalStateException("No vector fields configured for the collection.");
        }

        for (Map.Entry<String, VectorFieldInfo> entry : vectorFields.entrySet()) {
            String vectorFieldName = entry.getKey();
            VectorFieldInfo info = entry.getValue();
            if (info.getVectorFieldType() == null || info.getFieldName() == null || info.getFieldName().isEmpty()) {
                log.error("VectorFieldInfo for field '{}' is incomplete.", vectorFieldName);
                throw new IllegalStateException("Incomplete VectorFieldInfo for field: " + vectorFieldName);
            }
            // Optionally, validate the gRPC service URL and other properties
            if (info.getVectorGrpcService() == null || info.getVectorGrpcService().isEmpty()) {
                log.warn("VectorFieldInfo for field '{}' does not have a gRPC service configured.", vectorFieldName);
            }
        }

        log.info("All vector fields are properly configured.");
    }
}