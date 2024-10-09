package com.krickert.search.api.grpc.mapper.query;

import com.krickert.search.api.Filter;
import com.krickert.search.api.SearchRequest;
import com.krickert.search.api.SemanticOptions;
import com.krickert.search.api.SimilarityOptions;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.config.VectorFieldInfo;
import com.krickert.search.api.grpc.client.VectorService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class SemanticQueryBuilder {
    private static final Logger log = LoggerFactory.getLogger(SemanticQueryBuilder.class);
    private final VectorService vectorService;
    private final CollectionConfig collectionConfig;

    public SemanticQueryBuilder(VectorService vectorService, CollectionConfig collectionConfig) {
        this.vectorService = checkNotNull(vectorService);
        this.collectionConfig = collectionConfig;
    }

    /**
     * Builds and returns the semantic query string based on the SemanticOptions.
     *
     * @param semanticOptions The semantic search options.
     * @param request         The search request.
     * @return The semantic query string.
     */
    public String buildSemanticQuery(SemanticOptions semanticOptions, SearchRequest request) {
        List<VectorFieldInfo> vectorFieldsToUse = determineVectorFields(semanticOptions);
        List<Float> queryEmbedding = vectorService.getEmbeddingForText(request.getQuery());

        // Build vector queries for each vector field
        return vectorFieldsToUse.stream()
                .map(vectorFieldInfo -> vectorService.buildVectorQueryForEmbedding(vectorFieldInfo, queryEmbedding, semanticOptions.getTopK()))
                .collect(Collectors.joining(" OR "));
    }

    /**
     * Builds and returns the semantic boost query string based on the SemanticOptions and boost factor.
     *
     * @param semanticOptions The semantic search options.
     * @param request         The search request.
     * @param boost           The boost factor.
     * @return The semantic boost query string.
     */
    public String buildSemanticBoostQuery(SemanticOptions semanticOptions, SearchRequest request, float boost) {
        List<VectorFieldInfo> vectorFieldsToUse = determineVectorFields(semanticOptions);
        List<Float> queryEmbedding = vectorService.getEmbeddingForText(request.getQuery());

        // Build boost vector queries for each vector field
        return vectorFieldsToUse.stream()
                .map(vectorFieldInfo -> vectorService.buildVectorQueryForEmbedding(vectorFieldInfo, queryEmbedding, semanticOptions.getTopK()))
                .collect(Collectors.joining(" "));
    }

    /**
     * Handles similarity options, adding them to the params map.
     *
     * @param semanticOptions The semantic search options.
     * @param request         The search request.
     * @param params          The Solr query parameters map.
     */
    public void handleSimilarityOptions(SemanticOptions semanticOptions, SearchRequest request, Map<String, List<String>> params) {
        SimilarityOptions similarity = semanticOptions.hasSimilarity() ? semanticOptions.getSimilarity() : SimilarityOptions.getDefaultInstance();

        if (similarity.hasMinReturn()) {
            params.put("minReturn", Collections.singletonList(String.valueOf(similarity.getMinReturn())));
        } else {
            params.put("minReturn", Collections.singletonList("1")); // Default minReturn
        }

        if (similarity.hasMinTraverse()) {
            params.put("minTraverse", Collections.singletonList(String.valueOf(similarity.getMinTraverse())));
        } else {
            params.put("minTraverse", Collections.singletonList("-Infinity")); // Default minTraverse
        }

        // Apply pre-filters if any
        if (!similarity.getPreFilterList().isEmpty()) {
            for (Filter filter : similarity.getPreFilterList()) {
                String fq = filter.getField() + ":" + filter.getValue();
                params.computeIfAbsent("fq", k -> new ArrayList<>()).add(fq);
            }
        }

        log.debug("Similarity options applied: {}", similarity);
    }

    private List<VectorFieldInfo> determineVectorFields(SemanticOptions semanticOptions) {
        List<String> requestedVectorFields = semanticOptions.getVectorFieldsList();

        if (requestedVectorFields.isEmpty()) {
            // Use all configured vector fields if none are specified
            log.info("No vector fields specified in SemanticOptions. Using all configured vector fields.");
            return new ArrayList<>(collectionConfig.getVectorFields().values());
        } else {
            // Use only the specified vector fields
            List<VectorFieldInfo> vectorFields = new ArrayList<>();
            for (String fieldName : requestedVectorFields) {
                VectorFieldInfo info = collectionConfig.getVectorFieldsByName().get(fieldName);
                if (info == null) {
                    log.error("VectorFieldInfo not found for field: {}", fieldName);
                    throw new IllegalArgumentException("Vector field not found: " + fieldName);
                }
                vectorFields.add(info);
            }
            log.info("Using specified vector fields: {}", requestedVectorFields);
            return vectorFields;
        }
    }
}