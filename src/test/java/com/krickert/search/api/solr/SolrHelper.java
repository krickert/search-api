package com.krickert.search.api.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SolrHelper {

    public static void addDenseVectorField(SolrClient solrClient, String collectionName, String fieldName, int dimensions) throws Exception {
        String fieldTypeName = fieldName + dimensions;

        addVectorFieldType(solrClient, collectionName, fieldTypeName, dimensions);

        Map<String, Object> fieldAttributes = new HashMap<>();
        fieldAttributes.put("name", fieldName);
        fieldAttributes.put("type", fieldTypeName);

        SchemaRequest.AddField addFieldRequest = new SchemaRequest.AddField(fieldAttributes);

        SchemaResponse.UpdateResponse response = addFieldRequest.process(solrClient, collectionName);

        if (response.getStatus() != 0) {
            throw new RuntimeException("Failed to add vector field: " + response.getStatus() + ", " + response.getResponse());
        }
    }

    private static void addVectorFieldType(SolrClient solrClient, String collectionName, String fieldTypeName, int dimensions) throws Exception {
        Map<String, Object> fieldTypeAttributes = new HashMap<>();
        fieldTypeAttributes.put("name", fieldTypeName);
        fieldTypeAttributes.put("class", "solr.DenseVectorField");
        fieldTypeAttributes.put("vectorDimension", dimensions);
        fieldTypeAttributes.put("similarityFunction", "cosine");

        FieldTypeDefinition fieldTypeDefinition = new FieldTypeDefinition();
        fieldTypeDefinition.setAttributes(fieldTypeAttributes);

        SchemaRequest.AddFieldType addFieldTypeRequest = new SchemaRequest.AddFieldType(fieldTypeDefinition);

        SchemaResponse.UpdateResponse response = addFieldTypeRequest.process(solrClient, collectionName);

        if (response.getStatus() != 0) {
            throw new RuntimeException("Failed to add vector field type: " + response.getStatus() + ", " + response.getResponse());
        }
    }

    /**
     * Builds a vector query string for Solr using the provided field and vector embeddings.
     *
     * @param field The Solr field to search against.
     * @param embeddings The vector embeddings.
     * @param topK The number of top results to fetch.
     * @return The vector query string.
     */
    public static String buildVectorQuery(String field, List<Float> embeddings, int topK) {
        String vectorString = embeddings.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        return String.format("{!knn f=%s topK=%d}[%s]", field, topK, vectorString);
    }

    public static void addField(SolrClient solrClient, String name, String type, boolean multiValued, String collection) throws Exception {
        Map<String, Object> fieldAttributes = new HashMap<>();
        fieldAttributes.put("name", name);
        fieldAttributes.put("type", type);
        fieldAttributes.put("multiValued", multiValued);

        SchemaRequest.AddField addFieldUpdate = new SchemaRequest.AddField(fieldAttributes);
        SchemaResponse.UpdateResponse addFieldResponse = addFieldUpdate.process(solrClient, collection);

        assertNotNull(addFieldResponse);
        assertEquals(0, addFieldResponse.getStatus());
    }

}