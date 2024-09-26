package com.krickert.search.api.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.core.annotation.Introspected;

@EachProperty("search-api.solr.collection-config.vector-fields")
@JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
@Introspected
public class VectorFieldInfo {

    @JsonProperty("k")
    private Integer k;

    @JsonProperty("field-name")
    private String fieldName;

    @JsonProperty("vector-field-name")
    private String vectorFieldName;

    @JsonProperty("chunk-field")
    private boolean chunkField;

    @JsonProperty("chunk-collection")
    private String chunkCollection;

    @JsonProperty("vector-grpc-service")
    private String vectorGrpcService;


    // Getters and Setters

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getVectorFieldName() {
        return vectorFieldName;
    }

    public void setVectorFieldName(String vectorFieldName) {
        this.vectorFieldName = vectorFieldName;
    }

    public boolean isChunkField() {
        return chunkField;
    }

    public void setChunkField(boolean chunkField) {
        this.chunkField = chunkField;
    }

    public String getChunkCollection() {
        return chunkCollection;
    }

    public void setChunkCollection(String chunkCollection) {
        this.chunkCollection = chunkCollection;
    }

    public String getVectorGrpcService() {
        return vectorGrpcService;
    }

    public void setVectorGrpcService(String vectorGrpcService) {
        this.vectorGrpcService = vectorGrpcService;
    }

    public Integer getK() {
        return k;
    }

    public void setK(Integer k) {
        this.k = k;
    }
}