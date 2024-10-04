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
import static org.apache.solr.client.solrj.util.ClientUtils.escapeQueryChars;

@Singleton
public class KeywordQueryBuilder {
    private static final Logger log = LoggerFactory.getLogger(KeywordQueryBuilder.class);

    private final CollectionConfig collectionConfig;
    private final VectorService vectorService;

    public KeywordQueryBuilder(CollectionConfig collectionConfig, VectorService vectorService) {
        this.collectionConfig = checkNotNull(collectionConfig);
        this.vectorService = checkNotNull(vectorService);
    }

    /**
     * Adds keyword-specific parameters to the Solr query.
     *
     * @param keywordOptions The keyword search options.
     * @param request        The search request.
     * @param params         The Solr query parameters map.
     */
    public void addKeywordParams(KeywordOptions keywordOptions, SearchRequest request, Map<String, List<String>> params) {
        List<String> keywordFields = collectionConfig.getKeywordQueryFields();
        if (keywordFields.isEmpty()) {
            log.warn("No keyword query fields configured.");
            throw new IllegalStateException("No keyword query fields configured.");
        }

        // Additional keyword-specific parameters can be added here if needed
        // For example, applying filters or similarity settings

        log.debug("Keyword parameters added: {}", keywordOptions);
    }

    /**
     * Builds and returns the keyword query string based on the KeywordOptions.
     *
     * @param keywordOptions The keyword search options.
     * @param request        The search request.
     * @return The keyword query string.
     */
    public String buildKeywordQuery(KeywordOptions keywordOptions, SearchRequest request) {
        List<String> keywordFields = collectionConfig.getKeywordQueryFields();
        if (keywordFields.isEmpty()) {
            log.warn("No keyword query fields configured.");
            throw new IllegalStateException("No keyword query fields configured.");
        }

        // Build the keyword search query using OR between fields
        return keywordFields.stream()
                .map(field -> field + ":(" + escapeQueryChars(request.getQuery()) + ")")
                .collect(Collectors.joining(" OR "));
    }

    /**
     * Builds and returns the keyword boost query string based on the KeywordOptions and boost factor.
     *
     * @param keywordOptions The keyword search options.
     * @param request        The search request.
     * @param boost          The boost factor.
     * @return The keyword boost query string.
     */
    public String buildKeywordBoostQuery(KeywordOptions keywordOptions, SearchRequest request, float boost) {
        List<String> keywordFields = collectionConfig.getKeywordQueryFields();
        if (keywordFields.isEmpty()) {
            log.warn("No keyword query fields configured.");
            throw new IllegalStateException("No keyword query fields configured.");
        }

        // Build the keyword search query using OR between fields with boost
        return keywordFields.stream()
                .map(field -> field + ":(" + escapeQueryChars(request.getQuery()) + ")^" + boost)
                .collect(Collectors.joining(" OR "));
    }
}
