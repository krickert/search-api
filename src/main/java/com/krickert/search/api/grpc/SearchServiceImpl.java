package com.krickert.search.api.grpc;

import com.google.protobuf.Timestamp;
import com.krickert.search.api.*;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.config.SearchApiConfig;
import com.krickert.search.api.config.VectorFieldInfo;
import com.krickert.search.api.solr.SolrService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.RangeFacet;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.HighlightParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static io.micronaut.core.util.StringUtils.isNotEmpty;

@GrpcService
public class SearchServiceImpl extends SearchServiceGrpc.SearchServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

    private final SolrService solrService;
    private final VectorService vectorService;
    private final CollectionConfig collectionConfig;
    private final SearchApiConfig searchApiConfig;

    @Inject
    public SearchServiceImpl(SolrService solrService, VectorService vectorService, SearchApiConfig config) {
        this.solrService = solrService;
        this.vectorService = vectorService;
        this.collectionConfig = config.getSolr().getCollectionConfig();
        this.searchApiConfig = config;
    }

    @Override
    public void search(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
        try {
            // Build Solr query parameters based on the request and configuration
            SolrQueryData solrQueryData = buildSolrQueryParams(request);

            // Execute the Solr query
            QueryResponse solrResponse = solrService.query(collectionConfig.getCollectionName(), solrQueryData.queryParams);

            // Parse the Solr response into the gRPC SearchResponse
            SearchResponse searchResponse = parseSolrResponse(solrResponse, request, solrQueryData.fl);

            // Send the response back to the client
            responseObserver.onNext(searchResponse);
            responseObserver.onCompleted();
        } catch (SolrServerException e) {
            log.error("SolrServerException during search operation: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription("SolrServerException: " + e.getMessage()).withCause(e).asRuntimeException());
        } catch (IOException e) {
            log.error("IOException during search operation: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription("IOException: " + e.getMessage()).withCause(e).asRuntimeException());
        } catch (Exception e) {
            log.error("Unexpected error during search operation: {}", e.getMessage(), e);
            responseObserver.onError(Status.UNKNOWN.withDescription("Unexpected error: " + e.getMessage()).withCause(e).asRuntimeException());
        }
    }

    /**
     * Helper class to hold Solr query parameters and 'fl' parameter.
     */
    private static class SolrQueryData {
        Map<String, List<String>> queryParams;
        String fl;

        SolrQueryData(Map<String, List<String>> queryParams, String fl) {
            this.queryParams = queryParams;
            this.fl = fl;
        }
    }

    /**
     * Builds the Solr query parameters based on the SearchRequest and default configurations.
     *
     * @param request The SearchRequest from the client.
     * @return SolrQueryData containing the query parameters and 'fl' parameter.
     */
    private SolrQueryData buildSolrQueryParams(SearchRequest request) {
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

    /**
     * Handles the inclusion and exclusion of fields based on FieldList in the SearchRequest or defaults.
     *
     * @param request The SearchRequest from the client.
     * @param params  The current Solr query parameters.
     * @return The 'fl' parameter string.
     */
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

    /**
     * Adds unified facet requests to the Solr query parameters.
     *
     * @param request The SearchRequest from the client.
     * @param params  The current Solr query parameters.
     */
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

    /**
     * Enables highlighting based on the SearchRequest's HighlightOptions.
     *
     * @param request The SearchRequest from the client.
     * @param params  The current Solr query parameters.
     */
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

    /**
     * Adds semantic search parameters to the Solr query.
     *
     * @param semanticOptions The SemanticOptions from the SearchRequest.
     * @param request         The SearchRequest from the client.
     * @param params          The current Solr query parameters.
     */
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

    /**
     * Determines which vector fields to use based on SemanticOptions and configuration.
     *
     * @param semanticOptions The SemanticOptions from the SearchRequest.
     * @return A list of VectorFieldInfo objects to be used for semantic search.
     */
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

    /**
     * Adds keyword search parameters to the Solr query.
     *
     * @param keywordOptions The KeywordOptions from the SearchRequest.
     * @param request        The SearchRequest from the client.
     * @param params         The current Solr query parameters.
     */
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

    /**
     * Parses the Solr response and maps it to the gRPC SearchResponse.
     *
     * @param solrResponse The Solr QueryResponse.
     * @param request      The original SearchRequest.
     * @param fl           The 'fl' parameter string.
     * @return The mapped SearchResponse.
     */
    private SearchResponse parseSolrResponse(QueryResponse solrResponse, SearchRequest request, String fl) {
        SearchResponse.Builder responseBuilder = SearchResponse.newBuilder();

        // Determine the list of fields to include based on 'fl' parameter
        Set<String> includedFields = new HashSet<>();
        if (fl != null && !fl.isEmpty()) {
            String[] flParts = fl.split(",");
            for (String field : flParts) {
                field = field.trim();
                if (!field.startsWith("-")) {
                    includedFields.add(field);
                }
            }
        }

        // Map Solr documents to SearchResult
        for (SolrDocument doc : solrResponse.getResults()) {
            SearchResult.Builder resultBuilder = SearchResult.newBuilder();

            // Set document ID
            Object idObj = doc.getFieldValue("id");
            if (idObj != null) {
                resultBuilder.setId(idObj.toString());
            }

            // Dynamically add fields based on 'fl' parameter
            for (String field : includedFields) {
                Object value = doc.getFieldValue(field);
                if (value != null) {
                    resultBuilder.putFields(field, value.toString());
                }
            }

            // Add snippets (highlighting)
            if (request.hasHighlightOptions() && idObj != null) {
                Map<String, List<String>> highlighting = solrResponse.getHighlighting().get(idObj.toString());
                if (highlighting != null && !highlighting.isEmpty()) {
                    String snippet = buildSnippet(highlighting, request.getHighlightOptions());
                    resultBuilder.setSnippet(snippet);
                } else {
                    resultBuilder.setSnippet("");
                }
            }

            responseBuilder.addResults(resultBuilder.build());
        }

        // Handle facets
        Map<String, List<FacetResult>> facetsMap = new HashMap<>();

        // Handle Facet Fields
        if (solrResponse.getFacetFields() != null) {
            for (org.apache.solr.client.solrj.response.FacetField solrFacet : solrResponse.getFacetFields()) {
                List<FacetResult> facetResults = facetsMap.computeIfAbsent(solrFacet.getName(), k -> new ArrayList<>());
                for (org.apache.solr.client.solrj.response.FacetField.Count count : solrFacet.getValues()) {
                    FacetResult facetResult = FacetResult.newBuilder()
                            .setFacet(count.getName())
                            .setFacetCount(count.getCount())
                            .build();
                    facetResults.add(facetResult);
                }
            }
        }

        // Handle Facet Queries
        if (solrResponse.getFacetQuery() != null) {
            for (Map.Entry<String, Integer> entry : solrResponse.getFacetQuery().entrySet()) {
                List<FacetResult> facetResults = facetsMap.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
                FacetResult facetResult = FacetResult.newBuilder()
                        .setFacet(entry.getKey())
                        .setFacetCount(entry.getValue())
                        .build();
                facetResults.add(facetResult);
            }
        }

        // Handle Facet Ranges using Streams
        if (solrResponse.getFacetRanges() != null) {
            solrResponse.getFacetRanges().forEach(rangeFacet -> {
                String rangeField = rangeFacet.getName(); // Adjust according to SolrJ API
                List<FacetResult> facetResults = facetsMap.computeIfAbsent(rangeField, k -> new ArrayList<>());
                //noinspection unchecked
                rangeFacet.getCounts().forEach(countObj -> {
                    org.apache.solr.client.solrj.response.FacetField.Count count = (org.apache.solr.client.solrj.response.FacetField.Count) countObj;
                    FacetResult facetResult = FacetResult.newBuilder()
                            .setFacet(count.getName())
                            .setFacetCount(count.getCount())
                            .build();
                    facetResults.add(facetResult);
                });
            });
        }

        // Populate facets in the response
        for (Map.Entry<String, List<FacetResult>> entry : facetsMap.entrySet()) {
            FacetResults facetResults = FacetResults.newBuilder()
                    .addAllResults(entry.getValue())
                    .build();
            responseBuilder.putFacets(entry.getKey(), facetResults);
        }

        // Set total results and query time
        responseBuilder.setTotalResults(solrResponse.getResults().getNumFound());
        responseBuilder.setQTime(solrResponse.getQTime());

        // Set timestamp
        responseBuilder.setTimeOfSearch(Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
                .build());

        return responseBuilder.build();
    }

    /**
     * Builds a snippet string from the highlighting information.
     *
     * @param highlighting     The highlighting map from Solr.
     * @param highlightOptions The HighlightOptions from the SearchRequest.
     * @return A concatenated snippet string.
     */
    private String buildSnippet(Map<String, List<String>> highlighting, HighlightOptions highlightOptions) {
        List<String> snippets = new ArrayList<>();
        for (List<String> snippetList : highlighting.values()) {
            snippets.addAll(snippetList);
        }

        // Join snippets with a separator
        return String.join(" ... ", snippets);
    }
}