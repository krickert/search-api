package com.krickert.search.api.grpc.mapper.query;

import com.krickert.search.api.*;
import com.krickert.search.api.config.SearchApiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;
import java.util.*;
import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class SolrQueryBuilder {
    private static final Logger log = LoggerFactory.getLogger(SolrQueryBuilder.class);

    private final SearchApiConfig searchApiConfig;
    private final HighlighterQueryBuilder highlighterQueryBuilder;
    private final SemanticQueryBuilder semanticQueryBuilder;
    private final FacetQueryBuilder facetQueryBuilder;
    private final FieldListBuilder fieldListBuilder;
    private final KeywordQueryBuilder keywordQueryBuilder;

    public SolrQueryBuilder(SearchApiConfig config,
                            HighlighterQueryBuilder highlighterQueryBuilder,
                            SemanticQueryBuilder semanticQueryBuilder,
                            FacetQueryBuilder facetQueryBuilder,
                            FieldListBuilder fieldListBuilder,
                            KeywordQueryBuilder keywordQueryBuilder) {

        this.searchApiConfig = checkNotNull(config);
        this.highlighterQueryBuilder = checkNotNull(highlighterQueryBuilder);
        this.semanticQueryBuilder = checkNotNull(semanticQueryBuilder);
        this.facetQueryBuilder = checkNotNull(facetQueryBuilder);
        this.fieldListBuilder = checkNotNull(fieldListBuilder);
        this.keywordQueryBuilder = checkNotNull(keywordQueryBuilder);
        log.info("Solr query builder started.");
    }

    public SolrQueryData buildSolrQueryParams(SearchRequest request) {
        Map<String, List<String>> params = new HashMap<>();

        // Handle search strategies (semantic and/or keyword)
        if (request.hasStrategy()) {
            SearchStrategyOptions strategy = request.getStrategy();
            if (strategy.hasSemantic()) {
                semanticQueryBuilder.addSemanticParams(strategy.getSemantic(), request, params);
            }
            if (strategy.hasKeyword()) {
                keywordQueryBuilder.addKeywordParams(strategy.getKeyword(), request, params);
            }
            highlighterQueryBuilder.enableHighlighting(request, params); // Enable highlighting for keyword search
        }
        // Handle start and rows (paging)
        int start = request.hasStart() ? request.getStart() : 0;
        int numResults = request.hasNumResults() ? request.getNumResults() : searchApiConfig.getSolr().getDefaultSearch().getRows();
        params.put("start", Collections.singletonList(String.valueOf(start)));
        params.put("rows", Collections.singletonList(String.valueOf(numResults)));

        // Handle filter queries (fq)
        if (!request.getFilterQueriesList().isEmpty()) {
            params.put("fq", request.getFilterQueriesList());
        }

        // Handle sorting
        if (request.hasSort()) {
            SortOptions sortOptions = request.getSort();
            String sortField = (sortOptions.getSortType() == SortType.FIELD && sortOptions.hasSortField())
                    ? sortOptions.getSortField()
                    : "score"; // Default sort field
            String sortOrder = sortOptions.getSortOrder() == SortOrder.ASC ? "asc" : "desc";
            params.put("sort", Collections.singletonList(sortField + " " + sortOrder));
        } else {
            // Use default sort from configuration
            params.put("sort", Collections.singletonList(searchApiConfig.getSolr().getDefaultSearch().getSort()));
        }

        // Handle unified facets
        facetQueryBuilder.addUnifiedFacets(request, params);

        // Handle additional parameters
        if (request.hasAdditionalParams()) {
            request.getAdditionalParams().getParamList().forEach(param ->
                    params.computeIfAbsent(param.getField(), k -> new ArrayList<>()).add(param.getValue())
            );
        }

        // Handle field list (inclusion/exclusion)
        String fl = fieldListBuilder.handleFieldList(request, params);

        return new SolrQueryData(params, fl);
    }
}