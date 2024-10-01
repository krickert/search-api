package com.krickert.search.api.config;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CollectionConfigTest {

    @Test
    public void testVectorFieldsByNameMapping() {
        try (ApplicationContext context = ApplicationContext.run(
                "search-api.solr.collectionConfig.collectionName", "test-collection",
                "search-api.solr.collectionConfig.vectorFields.bodyVectorField.field-name", "body",
                "search-api.solr.collectionConfig.vectorFields.bodyVectorField.vector-field-name", "body-vector",
                "search-api.solr.collectionConfig.vectorFields.bodyVectorField.vector-field-type", "inline",
                "search-api.solr.collectionConfig.vectorFields.bodyVectorField.vector-grpc-service", "http://localhost:50401/vectorizer",
                "search-api.solr.collectionConfig.vectorFields.bodyVectorField.k", "10",
                "search-api.solr.collectionConfig.vectorFields.titleVectorField.field-name", "title",
                "search-api.solr.collectionConfig.vectorFields.titleVectorField.vector-field-name", "title-vector",
                "search-api.solr.collectionConfig.vectorFields.titleVectorField.vector-field-type", "inline",
                "search-api.solr.collectionConfig.vectorFields.titleVectorField.vector-grpc-service", "http://localhost:50401/vectorizer",
                "search-api.solr.collectionConfig.vectorFields.titleVectorField.k", "10")) {

            CollectionConfig config = context.getBean(CollectionConfig.class);
            assertNotNull(config);
            assertEquals("documents", config.getCollectionName());

            Map<String, VectorFieldInfo> vectorFieldsByName = config.getVectorFieldsByName();
            assertNotNull(vectorFieldsByName);
            assertEquals(2, vectorFieldsByName.size());

            VectorFieldInfo titleVector = vectorFieldsByName.get("title-vector");
            assertNotNull(titleVector);
            assertEquals("title", titleVector.getFieldName());
            assertEquals(VectorFieldType.INLINE, titleVector.getVectorFieldType());
            assertEquals("http://localhost:50401/vectorizer", titleVector.getVectorGrpcService());
            assertEquals(10, titleVector.getK());

            VectorFieldInfo bodyVector = vectorFieldsByName.get("body-vector");
            assertNotNull(bodyVector);
            assertEquals("body", bodyVector.getFieldName());
            assertEquals(VectorFieldType.INLINE, bodyVector.getVectorFieldType());
            assertEquals("http://localhost:50401/vectorizer", bodyVector.getVectorGrpcService());
            assertEquals(10, bodyVector.getK());
        }
    }
}