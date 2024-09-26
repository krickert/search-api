package com.krickert.search.api.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;

import java.util.HashMap;
import java.util.Map;

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
}