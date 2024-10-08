package com.krickert.search.api.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.google.common.base.MoreObjects;
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

    @JsonProperty("vector-field-type")
    private VectorFieldType vectorFieldType;

    @JsonProperty("chunk-collection")
    private String chunkCollection;

    @JsonProperty("vector-grpc-service")
    private String vectorGrpcService;

    // Getters and Setters

    public Integer getK() {
        return k;
    }

    public void setK(Integer k) {
        this.k = k;
    }

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

    public VectorFieldType getVectorFieldType() {
        return vectorFieldType;
    }

    public void setVectorFieldType(VectorFieldType vectorFieldType) {
        this.vectorFieldType = vectorFieldType;
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

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("k", k)
                .add("fieldName", fieldName)
                .add("vectorFieldName", vectorFieldName)
                .add("vectorFieldType", vectorFieldType)
                .add("chunkCollection", chunkCollection)
                .add("vectorGrpcService", vectorGrpcService)
                .toString();
    }
}