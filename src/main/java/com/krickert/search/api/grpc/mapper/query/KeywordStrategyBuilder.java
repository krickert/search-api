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
    public String buildKeywordQuery(KeywordOptions keywordOptions, SearchRequest request, float boost) {
        final String query = findQuery(keywordOptions, request);
        List<String> fieldsToUse = findQueryFields(keywordOptions);
        // Build the keyword search query using OR between fields
        float boostFactor = boost == 0.0f ? 1.0f : boost;
        String eDismaxQuery= "{!edismax q.op=" +
                keywordOptions.getKeywordLogicalOperator().name() +
                " qf=\"" + String.join(" ", fieldsToUse) +
                "\" }(" + query + ")^" + boostFactor;
        log.debug("Query we are sending for strategy: [keywordOptions: {} query: {}]", keywordOptions, eDismaxQuery);
        return eDismaxQuery;
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
