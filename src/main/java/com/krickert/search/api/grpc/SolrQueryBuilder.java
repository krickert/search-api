package com.krickert.search.api.grpc;

import com.krickert.search.api.*;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.config.SearchApiConfig;
import com.krickert.search.api.config.VectorFieldInfo;
import io.micronaut.core.util.CollectionUtils;
import org.apache.solr.common.params.HighlightParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

import static io.micronaut.core.util.StringUtils.isNotEmpty;

@Singleton
public class SolrQueryBuilder {
    private static final Logger log = LoggerFactory.getLogger(SolrQueryBuilder.class);

    private final VectorService vectorService;
    private final CollectionConfig collectionConfig;
    private final SearchApiConfig searchApiConfig;

    public SolrQueryBuilder(VectorService vectorService, SearchApiConfig config) {
        this.vectorService = vectorService;
        this.collectionConfig = config.getSolr().getCollectionConfig();
        this.searchApiConfig = config;
    }

    public SolrQueryData buildSolrQueryParams(SearchRequest request) {
        Map<String, List<String>> params = new HashMap<>();

        // Handle search strategies (semantic and/or keyword)
        if (request.hasStrategy()) {
            SearchStrategyOptions strategy = request.getStrategy();
            if (strategy.hasSemantic()) {
                addSemanticParams(strategy.getSemantic(), request, params);
            }
            if (strategy.hasKeyword()) {
                addKeywordParams(strategy.getKeyword(), request, params);
                enableHighlighting(request, params); // Enable highlighting for keyword search
            }
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
        addUnifiedFacets(request, params);

        // Handle additional parameters
        if (request.hasAdditionalParams()) {
            request.getAdditionalParams().getParamList().forEach(param ->
                    params.computeIfAbsent(param.getField(), k -> new ArrayList<>()).add(param.getValue())
            );
        }

        // Handle field list (inclusion/exclusion)
        String fl = handleFieldList(request, params);

        return new SolrQueryData(params, fl);
    }

    private String handleFieldList(SearchRequest request, Map<String, List<String>> params) {
        String fl;
        if (request.hasFieldList()) {
            FieldList fieldList = request.getFieldList();
            List<String> inclusionFields = fieldList.getInclusionFieldsList();
            List<String> exclusionFields = fieldList.getExclusionFieldsList();

            List<String> flParts = new ArrayList<>();

            // Add inclusion fields
            if (!inclusionFields.isEmpty()) {
                flParts.addAll(inclusionFields);
            }

            // Add exclusion fields with '-' prefix
            if (!exclusionFields.isEmpty()) {
                List<String> excluded = exclusionFields.stream()
                        .map(field -> "-" + field)
                        .toList();
                flParts.addAll(excluded);
            }

            // Detect conflicts (fields in both inclusion and exclusion)
            Set<String> includedSet = new HashSet<>(inclusionFields);
            Set<String> excludedSet = new HashSet<>(exclusionFields);
            includedSet.retainAll(excludedSet);
            if (!includedSet.isEmpty()) {
                log.warn("Fields {} are both included and excluded. They will be excluded.", includedSet);
                // Remove conflicting fields from inclusion
                flParts.removeAll(includedSet);
                excludedSet.forEach(field -> flParts.add("-" + field));
            }

            fl = String.join(",", flParts);
        } else {
            // Use default fields from configuration
            List<String> defaultFields = searchApiConfig.getSolr().getCollectionConfig().getDefaultFields();
            if (CollectionUtils.isNotEmpty(defaultFields)) {
                fl = String.join(",", defaultFields);
            } else {
                fl = "*";
            }
        }
        params.put("fl", Collections.singletonList(fl));
        log.debug("Set 'fl' parameter to: {}", fl);

        return fl;
    }

    private void addUnifiedFacets(SearchRequest request, Map<String, List<String>> params) {
        for (FacetRequest facetRequest : request.getFacetRequestsList()) {
            switch (facetRequest.getFacetTypeCase()) {
                case FACETFIELD:
                    FacetField facetField = facetRequest.getFacetField();
                    params.computeIfAbsent("facet.field", k -> new ArrayList<>()).add(facetField.getField());
                    if (facetField.hasLimit()) {
                        params.put("f." + facetField.getField() + ".facet.limit",
                                Collections.singletonList(String.valueOf(facetField.getLimit())));
                    }
                    if (facetField.hasMissing()) {
                        params.put("f." + facetField.getField() + ".facet.missing",
                                Collections.singletonList(String.valueOf(facetField.getMissing())));
                    }
                    if (facetField.hasPrefix()) {
                        params.put("f." + facetField.getField() + ".facet.prefix",
                                Collections.singletonList(facetField.getPrefix()));
                    }
                    break;

                case FACETRANGE:
                    FacetRange facetRange = facetRequest.getFacetRange();
                    params.computeIfAbsent("facet.range", k -> new ArrayList<>()).add(facetRange.getField());
                    if (facetRange.hasStart()) {
                        params.put("f." + facetRange.getField() + ".facet.range.start",
                                Collections.singletonList(facetRange.getStart()));
                    }
                    if (facetRange.hasEnd()) {
                        params.put("f." + facetRange.getField() + ".facet.range.end",
                                Collections.singletonList(facetRange.getEnd()));
                    }
                    if (facetRange.hasGap()) {
                        params.put("f." + facetRange.getField() + ".facet.range.gap",
                                Collections.singletonList(facetRange.getGap()));
                    }
                    if (facetRange.hasHardend()) {
                        params.put("f." + facetRange.getField() + ".facet.range.hardend",
                                Collections.singletonList(String.valueOf(facetRange.getHardend())));
                    }
                    if (facetRange.hasOther()) {
                        params.put("f." + facetRange.getField() + ".facet.range.other",
                                Collections.singletonList(facetRange.getOther()));
                    }
                    break;

                case FACETQUERY:
                    FacetQuery facetQuery = facetRequest.getFacetQuery();
                    params.computeIfAbsent("facet.query", k -> new ArrayList<>()).add(facetQuery.getQuery());
                    break;

                case FACETTYPE_NOT_SET:
                default:
                    log.warn("Encountered FacetRequest with no facet type set.");
                    break;
            }
        }
    }

    private void enableHighlighting(SearchRequest request, Map<String, List<String>> params) {
        if (request.hasHighlightOptions()) {
            HighlightOptions highlight = request.getHighlightOptions();
            params.put(HighlightParams.HIGHLIGHT, Collections.singletonList("true"));

            if (!highlight.getFieldsList().isEmpty()) {
                String fieldsToHighlight = String.join(",", highlight.getFieldsList());
                params.put(HighlightParams.FIELDS, Collections.singletonList(fieldsToHighlight));
            }

            if (isNotEmpty(highlight.getPreTag())) {
                params.put(HighlightParams.SIMPLE_PRE, Collections.singletonList(highlight.getPreTag()));
            } else {
                params.put(HighlightParams.SIMPLE_PRE, Collections.singletonList("<em>"));
            }

            if (isNotEmpty(highlight.getPostTag())) {
                params.put(HighlightParams.SIMPLE_POST, Collections.singletonList(highlight.getPostTag()));
            } else {
                params.put(HighlightParams.SIMPLE_POST, Collections.singletonList("</em>"));
            }

            if (highlight.getSnippetCount() > 0) {
                params.put(HighlightParams.SNIPPETS, Collections.singletonList(String.valueOf(highlight.getSnippetCount())));
            } else {
                params.put(HighlightParams.SNIPPETS, Collections.singletonList("1")); // Default to 1 snippet
            }

            if (highlight.getSnippetSize() > 0) {
                params.put(HighlightParams.FRAGSIZE, Collections.singletonList(String.valueOf(highlight.getSnippetSize())));
            } else {
                params.put(HighlightParams.FRAGSIZE, Collections.singletonList("100")); // Default snippet size
            }

            // Handle semantic-specific highlighting if applicable
            if (highlight.getSemanticHighlight()) {
                // Implement semantic highlighting logic as needed
                // This might involve highlighting entire chunks instead of snippets
                // Depending on your Solr schema and data structure
                log.debug("Semantic highlighting enabled.");
            }

            log.debug("Highlighting parameters set: {}", params);
        }
    }

    private void addSemanticParams(SemanticOptions semanticOptions, SearchRequest request, Map<String, List<String>> params) {
        List<VectorFieldInfo> vectorFieldsToUse = determineVectorFields(semanticOptions);

        // Retrieve the embedding for the query text
        List<Float> queryEmbedding = vectorService.getEmbeddingForText(request.getQuery());

        // Build vector queries for each vector field
        List<String> vectorQueries = vectorFieldsToUse.stream()
                .map(vectorFieldInfo -> vectorService.buildVectorQueryForEmbedding(vectorFieldInfo, queryEmbedding, semanticOptions.getTopK()))
                .collect(Collectors.toList());

        // Combine vector queries using logical operators
        String combinedVectorQuery = String.join(" OR ", vectorQueries);
        params.put("q", Collections.singletonList(combinedVectorQuery));

        // Handle similarity options
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

        log.debug("Semantic search parameters set: {}", params);
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

    private void addKeywordParams(KeywordOptions keywordOptions, SearchRequest request, Map<String, List<String>> params) {
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