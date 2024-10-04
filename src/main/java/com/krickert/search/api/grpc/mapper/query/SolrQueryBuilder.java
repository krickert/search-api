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
    private final SemanticQueryBuilder semanticQueryBuilder;
    private final FacetQueryBuilder facetQueryBuilder;
    private final FieldListBuilder fieldListBuilder;
    private final KeywordQueryBuilder keywordQueryBuilder;

    @Inject
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
        if (request.hasStrategy() && request.getStrategy().getStrategiesCount() > 0) {
            SearchStrategyOptions strategyOptions = request.getStrategy();
            LogicalOperator operator = strategyOptions.getOperator();

            List<String> mainQueries = new ArrayList<>();
            List<String> boostQueries = new ArrayList<>();

            for (SearchStrategy strategy : strategyOptions.getStrategiesList()) {
                switch (strategy.getType()) {
                    case KEYWORD:
                        // Add keyword-specific parameters (e.g., filters, similarity options)
                        keywordQueryBuilder.addKeywordParams(strategy.getKeyword(), request, params);
                        // Build the main keyword query
                        String keywordQuery = keywordQueryBuilder.buildKeywordQuery(strategy.getKeyword(), request);
                        mainQueries.add(keywordQuery);
                        // Build the keyword boost query if boost factor is present
                        if (strategy.hasBoost()) {
                            String keywordBoostQuery = keywordQueryBuilder.buildKeywordBoostQuery(strategy.getKeyword(), request, strategy.getBoost());
                            boostQueries.add(keywordBoostQuery);
                        }
                        break;
                    case SEMANTIC:
                        // Add semantic-specific parameters (e.g., similarity options, filters)
                        semanticQueryBuilder.handleSimilarityOptions(strategy.getSemantic(), request, params);
                        // Build the main semantic query
                        String semanticQuery = semanticQueryBuilder.buildSemanticQuery(strategy.getSemantic(), request);
                        mainQueries.add(semanticQuery);
                        // Build the semantic boost query if boost factor is present
                        if (strategy.hasBoost()) {
                            String semanticBoostQuery = semanticQueryBuilder.buildSemanticBoostQuery(strategy.getSemantic(), request, strategy.getBoost());
                            boostQueries.add(semanticBoostQuery);
                        }
                        break;
                    // Handle additional strategy types here
                    default:
                        throw new IllegalArgumentException("Unsupported strategy type: " + strategy.getType());
                }
            }

            // Combine main queries with the specified operator
            String combinedMainQuery = String.join(" " + operatorToSolrOperator(operator) + " ", mainQueries);
            params.put("q", Collections.singletonList(combinedMainQuery));

            // Add boost queries if any
            if (!boostQueries.isEmpty()) {
                params.put("bq", boostQueries);
            }

            // Enable highlighting based on the operator
            highlighterQueryBuilder.enableHighlighting(request, params, operator);
        } else {
            // Default to keyword-only search if no strategy is specified
            KeywordOptions defaultKeywordOptions = getDefaultKeywordOptions();
            keywordQueryBuilder.addKeywordParams(defaultKeywordOptions, request, params);
            String keywordQuery = keywordQueryBuilder.buildKeywordQuery(defaultKeywordOptions, request);
            params.put("q", Collections.singletonList(keywordQuery));
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

    private KeywordOptions getDefaultKeywordOptions() {
        return KeywordOptions.newBuilder().setBoostWithSemantic(true).build();
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