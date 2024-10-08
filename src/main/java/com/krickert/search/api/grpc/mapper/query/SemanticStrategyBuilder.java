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
public class SemanticStrategyBuilder {
    private static final Logger log = LoggerFactory.getLogger(SemanticStrategyBuilder.class);
    private final VectorService vectorService;
    private final CollectionConfig collectionConfig;

    public SemanticStrategyBuilder(VectorService vectorService, CollectionConfig collectionConfig) {
        this.vectorService = checkNotNull(vectorService);
        this.collectionConfig = collectionConfig;
    }

    /**
     * Builds and returns the semantic query string based on the SemanticOptions.
     *
     * @param semanticOptions The semantic search options.
     * @param request         The search request.
     * @param params          Solr parameter object
     * @return The semantic query string.
     */
    public String buildSemanticQuery(SemanticOptions semanticOptions, SearchRequest request, float boost, Map<String, List<String>> params) {
        List<VectorFieldInfo> vectorFieldsToUse = determineVectorFields(semanticOptions);
        List<Float> queryEmbedding = vectorService.getEmbeddingForText(request.getQuery());

        // Prepare the vector string representation
        String vectorString = "[" + queryEmbedding.stream()
                .map(embedding -> String.format("%.6f", embedding))
                .collect(Collectors.joining(",")) + "]";
        params.put("vector", Collections.singletonList(vectorString));

        // Create vector queries for each vector field
        return vectorFieldsToUse.stream()
                .map(vectorFieldInfo -> {
                    String vectorQuery = vectorService.buildVectorQueryForEmbedding(vectorFieldInfo, queryEmbedding, semanticOptions.getTopK(), boost);
                    // Normalize score using a custom normalization approach (example)
                    return String.format("scale(%s, 0, 1)^%.2f", vectorQuery, boost);
                })
                .collect(Collectors.joining(" OR "));
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
