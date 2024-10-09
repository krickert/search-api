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
        validateSimilarityOptions(semanticOptions);

        List<VectorFieldInfo> vectorFieldsToUse = determineVectorFields(semanticOptions);
        List<Float> queryEmbedding = vectorService.getEmbeddingForText(request.getQuery());

        // Prepare the vector string representation
        String vectorString = "[" + queryEmbedding.stream()
                .map(embedding -> String.format("%.6f", embedding))
                .collect(Collectors.joining(",")) + "]";
        String vectorVariable = String.format("vectorQuery_%d", params.size() + 1);
        params.put(vectorVariable, Collections.singletonList(vectorString));

        boolean useVectorSimilarity = semanticOptions.hasSimilarity() && (semanticOptions.getSimilarity().hasMinReturn() || semanticOptions.getSimilarity().hasMinTraverse());
        String queryParser = useVectorSimilarity ? "vectorSimilarity" : "knn";

        // Create vector queries for each vector field with similarity options
        return vectorFieldsToUse.stream()
                .map(vectorFieldInfo -> {
                    StringBuilder vectorQueryBuilder = new StringBuilder();
                    vectorQueryBuilder.append(String.format("{!%s f=%s v=$%s", queryParser, vectorFieldInfo.getVectorFieldName(), vectorVariable));

                    // Handle similarity options for vectorSimilarity
                    if (useVectorSimilarity) {
                        SimilarityOptions similarity = semanticOptions.getSimilarity();
                        if (similarity.hasMinReturn()) {
                            vectorQueryBuilder.append(String.format(" minReturn=%s", similarity.getMinReturn()));
                        }
                        if (similarity.hasMinTraverse()) {
                            vectorQueryBuilder.append(String.format(" minTraverse=%s", similarity.getMinTraverse()));
                        }
                    }

                    if (!semanticOptions.getIncludeTagsList().isEmpty()) {
                        String includeTags = String.join(",", semanticOptions.getIncludeTagsList());
                        vectorQueryBuilder.append(String.format(" includeTags='%s'", includeTags));
                    }
                    if (!semanticOptions.getExcludeTagsList().isEmpty()) {
                        String excludeTags = String.join(",", semanticOptions.getExcludeTagsList());
                        vectorQueryBuilder.append(String.format(" excludeTags='%s'", excludeTags));
                    }

                    // Handle preFilters if specified
                    if (!semanticOptions.getSimilarity().getPreFilterList().isEmpty()) {
                        semanticOptions.getSimilarity().getPreFilterList().forEach(preFilter -> {
                            String knnPreFilter = String.format("%s:%s", preFilter.getField(), preFilter.getValue());
                            params.computeIfAbsent("knnPreFilter", k -> new ArrayList<>()).add(knnPreFilter);
                        });
                        vectorQueryBuilder.append(" preFilter=$knnPreFilter");
                    }

                    vectorQueryBuilder.append("}");

                    // Normalize score using a custom normalization approach (example)
                    return String.format("scale(%s, 0, 1)^%.2f", vectorQueryBuilder.toString(), boost);
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

    private void validateSimilarityOptions(SemanticOptions semanticOptions) {
        SimilarityOptions similarity = semanticOptions.hasSimilarity() ? semanticOptions.getSimilarity() : SimilarityOptions.getDefaultInstance();
        if (!similarity.getPreFilterList().isEmpty() && (!semanticOptions.getIncludeTagsList().isEmpty() || !semanticOptions.getExcludeTagsList().isEmpty())) {
            throw new IllegalArgumentException("The preFilter cannot be used with includeTags or excludeTags. Please specify only one.");
        }
    }
}
