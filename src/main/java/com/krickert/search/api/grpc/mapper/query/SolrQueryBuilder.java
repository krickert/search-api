package com.krickert.search.api.grpc.mapper.query;

import com.krickert.search.api.*;
import com.krickert.search.api.config.SearchApiConfig;
import jakarta.inject.Inject;
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
    private final SemanticStrategyBuilder semanticStrategyBuilder;
    private final FacetQueryBuilder facetQueryBuilder;
    private final FieldListBuilder fieldListBuilder;
    private final KeywordStrategyBuilder keywordStrategyBuilder;

    @Inject
    public SolrQueryBuilder(SearchApiConfig config,
                            HighlighterQueryBuilder highlighterQueryBuilder,
                            SemanticStrategyBuilder semanticStrategyBuilder,
                            FacetQueryBuilder facetQueryBuilder,
                            FieldListBuilder fieldListBuilder,
                            KeywordStrategyBuilder keywordStrategyBuilder) {

        this.searchApiConfig = checkNotNull(config);
        this.highlighterQueryBuilder = checkNotNull(highlighterQueryBuilder);
        this.semanticStrategyBuilder = checkNotNull(semanticStrategyBuilder);
        this.facetQueryBuilder = checkNotNull(facetQueryBuilder);
        this.fieldListBuilder = checkNotNull(fieldListBuilder);
        this.keywordStrategyBuilder = checkNotNull(keywordStrategyBuilder);
        log.info("Solr query builder started.");
    }

    public SolrQueryData buildSolrQueryParams(SearchRequest request) {
        Map<String, List<String>> params = new HashMap<>();

        // Handle search strategies (semantic and/or keyword)
        if (request.hasStrategy() && request.getStrategy().getStrategiesCount() > 0) {
            SearchStrategyOptions strategyOptions = request.getStrategy();
            LogicalOperator operator = strategyOptions.getOperator();
            List<String> mainQueries = createQueriesFromStrategies(request, strategyOptions, params);
            // Combine main queries with the specified operator
            String combinedMainQuery = String.join(" " + operator.name() + " ", mainQueries);
            params.put("q", Collections.singletonList(combinedMainQuery));

            // Enable highlighting based on the operator
            highlighterQueryBuilder.enableHighlighting(request, params, operator);
        } else {
            // Default to keyword-only search if no strategy is specified
            KeywordOptions defaultKeywordOptions = getDefaultKeywordOptions();
            String keywordQuery = keywordStrategyBuilder.buildKeywordQuery(defaultKeywordOptions, request, 1.0f);
            params.put("q", Collections.singletonList(keywordQuery));
        }
        addPaginationToRequest(request, params);
        addFilterQueriesToRequest(request, params);
        addSortingOptions(request, params);
        addAdditionalFields(request, params);
        fieldListBuilder.handleFieldList(request, params);
        facetQueryBuilder.addUnifiedFacets(request, params);
        return new SolrQueryData(params);
    }

    private static void addAdditionalFields(SearchRequest request, Map<String, List<String>> params) {
        // Handle additional parameters
        if (request.hasAdditionalParams()) {
            request.getAdditionalParams().getParamList().forEach(param ->
                    params.computeIfAbsent(param.getField(), k -> new ArrayList<>()).add(param.getValue())
            );
        }
    }

    private void addSortingOptions(SearchRequest request, Map<String, List<String>> params) {
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
    }

    private static void addFilterQueriesToRequest(SearchRequest request, Map<String, List<String>> params) {
        // Handle filter queries (fq)
        if (!request.getFilterQueriesList().isEmpty()) {
            params.put("fq", request.getFilterQueriesList());
        }
    }

    private void addPaginationToRequest(SearchRequest request, Map<String, List<String>> params) {
        int start = request.hasStart() ? request.getStart() : 0;
        int numResults = request.hasNumResults() ? request.getNumResults() : searchApiConfig.getSolr().getDefaultSearch().getRows();
        params.put("start", Collections.singletonList(String.valueOf(start)));
        params.put("rows", Collections.singletonList(String.valueOf(numResults)));
    }

    private List<String> createQueriesFromStrategies(SearchRequest request, SearchStrategyOptions strategyOptions, Map<String, List<String>> params) {
        List<String> mainQueries = new ArrayList<>();
        for (SearchStrategy strategy : strategyOptions.getStrategiesList()) {
            switch (strategy.getType()) {
                case KEYWORD -> {
                    // Build the main keyword query
                    String keywordQuery = keywordStrategyBuilder.buildKeywordQuery(strategy.getKeyword(), request, strategy.getBoost());
                    mainQueries.add(keywordQuery);
                }
                case SEMANTIC -> {
                    // Add semantic-specific parameters (e.g., similarity options, filters)
                    semanticStrategyBuilder.handleSimilarityOptions(strategy.getSemantic(), request, params);
                    // Build the main semantic query
                    String semanticQuery = semanticStrategyBuilder.buildSemanticQuery(strategy.getSemantic(), request,
                            strategy.getBoost(), params);
                    mainQueries.add(semanticQuery);
                }
                // Handle additional strategy types here
                default -> throw new IllegalArgumentException("Unsupported strategy type: " + strategy.getType());
            }
        }
        return mainQueries;
    }

    private KeywordOptions getDefaultKeywordOptions() {
        return KeywordOptions.newBuilder()
                .setKeywordLogicalOperator(
                        LogicalOperator.OR).build();
    }

    // Helper method to convert LogicalOperator enum to Solr operator string
    private String operatorToSolrOperator(LogicalOperator operator) {
        return switch (operator) {
            case AND -> "AND";
            case OR -> "OR";
            default -> "OR"; // Default operator
        };
    }
}
