package com.krickert.search.api.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("search-api.solr.collection-config")
@Introspected
public class CollectionConfig {

    @JsonProperty("collection-name")
    private String collectionName;

    @JsonProperty("keyword-query-fields")
    private List<String> keywordQueryFields;

    private final Map<String, VectorFieldInfo> vectorFields;

    // Constructor for injection
    public CollectionConfig(Map<String, VectorFieldInfo> vectorFields) {
        this.vectorFields = vectorFields;
    }

    // Getters and Setters
    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public List<String> getKeywordQueryFields() {
        return keywordQueryFields;
    }

    public void setKeywordQueryFields(List<String> keywordQueryFields) {
        this.keywordQueryFields = keywordQueryFields;
    }

    public Map<String, VectorFieldInfo> getVectorFields() {
        return vectorFields;
    }
}