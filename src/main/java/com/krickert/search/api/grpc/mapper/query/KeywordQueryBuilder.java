package com.krickert.search.api.grpc.mapper.query;

import com.krickert.search.api.KeywordOptions;
import com.krickert.search.api.SearchRequest;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.grpc.client.VectorService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class KeywordQueryBuilder {
    private static final Logger log = LoggerFactory.getLogger(KeywordQueryBuilder.class);

    private final CollectionConfig collectionConfig;
    private final VectorService vectorService;

    public KeywordQueryBuilder(CollectionConfig collectionConfig, VectorService vectorService) {
        this.collectionConfig = checkNotNull(collectionConfig);
        this.vectorService = checkNotNull(vectorService);
    }

    public void addKeywordParams(KeywordOptions keywordOptions, SearchRequest request, Map<String, List<String>> params) {
        List<String> keywordFields = collectionConfig.getKeywordQueryFields();
        if (keywordFields.isEmpty()) {
            log.warn("No keyword query fields configured.");
            throw new IllegalStateException("No keyword query fields configured.");
        }

        // Build the keyword search query using OR between fields
        String keywordQuery = keywordFields.stream()
                .map(field -> field + ":(" + request.getQuery() + ")")
                .collect(Collectors.joining(" OR "));
        params.put("q", Collections.singletonList(keywordQuery));

        // Apply boosting with semantic if enabled
        if (keywordOptions.getBoostWithSemantic()) {
            List<Float> queryEmbedding = vectorService.getEmbeddingForText(request.getQuery());

            // Build boost queries for each vector field
            List<String> boostQueries = collectionConfig.getVectorFields().values().stream()
                    .map(vectorFieldInfo -> vectorService.buildVectorQueryForEmbedding(vectorFieldInfo, queryEmbedding, vectorFieldInfo.getK()))
                    .collect(Collectors.toList());

            // Combine boost queries
            String combinedBoostQuery = String.join(" ", boostQueries);
            params.put("bq", Collections.singletonList(combinedBoostQuery));

            log.debug("Boost queries applied: {}", combinedBoostQuery);
        }

        log.debug("Keyword search parameters set: {}", params);
    }
}
