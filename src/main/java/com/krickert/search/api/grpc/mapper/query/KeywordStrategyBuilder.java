// ========================
// KeywordStrategyBuilder
// ========================

package com.krickert.search.api.grpc.mapper.query;

import com.google.common.collect.Lists;
import com.krickert.search.api.KeywordOptions;
import com.krickert.search.api.SearchRequest;
import com.krickert.search.api.config.CollectionConfig;
import io.micronaut.core.util.CollectionUtils;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.solr.client.solrj.util.ClientUtils.escapeQueryChars;

@Singleton
public class KeywordStrategyBuilder {
    private static final Logger log = LoggerFactory.getLogger(KeywordStrategyBuilder.class);

    private final CollectionConfig collectionConfig;

    public KeywordStrategyBuilder(CollectionConfig collectionConfig) {
        this.collectionConfig = checkNotNull(collectionConfig);
    }

    /**
     * Builds and returns the keyword query string based on the KeywordOptions.
     *
     * @param keywordOptions The keyword search options.
     * @param request        The search request.
     * @return The keyword query string.
     */
    public String buildKeywordQuery(KeywordOptions keywordOptions, SearchRequest request, float boost,
                                    Map<String, List<String>> params, AtomicInteger counter) {
        final String query = findQuery(keywordOptions, request);
        List<String> fieldsToUse = findQueryFields(keywordOptions);
        String escapedQuery = escapeQueryChars(query);
        int keywordQueryCounter = counter.incrementAndGet();
        String keywordQueryName = "keywordQuery_" + keywordQueryCounter;
        // Define the keyword query as a variable
        String keywordQueryVariable = String.format("%s=%s", keywordQueryName, escapedQuery);
        params.put(keywordQueryName, List.of(escapedQuery));

        // Build the edismax query to be used as a boost or main query
        String boostFactor = (boost > 0.0f) ? "^" + boost : "";
        String eDismaxQuery = String.format("{!edismax q.op=%s qf=\"%s\" v=$" + keywordQueryName + "}%s",
                keywordOptions.getKeywordLogicalOperator().name(),
                String.join(" ", fieldsToUse),
                boostFactor);

        // Normalize BM25 keyword boosts
        String normalizedQuery = String.format("scale(%s, 0, 1)", eDismaxQuery);

        log.debug("Query variable defined for strategy: [keywordOptions: {} query: {}]", keywordOptions, keywordQueryVariable);
        log.debug("Boosted and normalized Query for keyword strategy: [query: {}]", normalizedQuery);
        return normalizedQuery;
    }

    private List<String> findQueryFields(KeywordOptions keywordOptions) {
        List<String> fieldsToUse = Lists.newArrayList();
        if (keywordOptions.getOverrideFieldsToQueryCount() > 0) {
            fieldsToUse.addAll(keywordOptions.getOverrideFieldsToQueryList());
        } else {
            fieldsToUse.addAll(collectionConfig.getKeywordQueryFields());
        }
        return fieldsToUse;
    }

    private static String findQuery(KeywordOptions keywordOptions, SearchRequest request) {
        if (keywordOptions.hasQueryTextOverride()) {
            return keywordOptions.getQueryTextOverride();
        } else {
            return request.getQuery();
        }
    }
}