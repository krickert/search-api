package com.krickert.search.api.grpc;

import com.google.protobuf.Timestamp;
import com.krickert.search.api.*;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.config.SearchApiConfig;
import com.krickert.search.api.config.VectorFieldInfo;
import com.krickert.search.api.solr.SolrService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.HighlightParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@GrpcService
public class SearchServiceImpl extends SearchServiceGrpc.SearchServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

    private final SolrService solrService;
    private final VectorService vectorService;
    private final CollectionConfig collectionConfig;

    @Inject
    public SearchServiceImpl(SolrService solrService, VectorService vectorService, SearchApiConfig config) {
        this.solrService = solrService;
        this.vectorService = vectorService;
        this.collectionConfig = config.getSolr().getCollectionConfig();  // Use injected config
    }

    @Override
    public void search(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
        try {
            // Build Solr query params dynamically
            Map<String, String> queryParams = buildSolrQueryParams(request);

            // Execute Solr query
            QueryResponse solrResponse = solrService.query(collectionConfig.getCollectionName(), queryParams);

            // Parse Solr response into gRPC response
            SearchResponse searchResponse = parseSolrResponse(solrResponse, request);

            // Send response
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

    private Map<String, String> buildSolrQueryParams(SearchRequest request) {
        Map<String, String> params = new HashMap<>();

        // Handle different search strategies (keyword/semantic)
        if (request.getStrategy().hasSemantic()) {
            addSemanticParams(request.getStrategy().getSemantic(), request, params);
        } else if (request.getStrategy().hasKeyword()) {
            addKeywordParams(request.getStrategy().getKeyword(), request, params);
            enableHighlighting(params);  // Enable highlighting for keyword search
        }

        int start = request.hasStart() ? request.getStart() : 0;
        int numResults = request.hasNumResults() ? request.getNumResults() : 10; // Default to 10 or a configured default

        params.put("start", String.valueOf(start));
        params.put("rows", String.valueOf(numResults));

        // Filter Queries (fq)
        request.getFilterQueriesList().forEach(filter -> params.put("fq", filter));

        // Sorting
        if (request.hasSort()) {
            SortOptions sortOptions = request.getSort();
            String sortField = sortOptions.getSortType() == SortType.SCORE ? "score" : sortOptions.getSortField();
            String sortOrder = sortOptions.getSortOrder().name().toLowerCase();
            params.put("sort", sortField + " " + sortOrder);
        } else {
            // Default sorting by score desc
            params.put("sort", "score desc");
        }

        // Facets
        addFacetFields(request, params);
        // Facet Ranges
        addFacetRanges(request, params);

        // Query Facets
        request.getFacetQueriesList().forEach(facetQuery -> params.put("facet.query", facetQuery.getQuery()));

        // Additional Parameters
        if (request.hasAdditionalParams()) {
            request.getAdditionalParams().getParamList().forEach(param -> params.put(param.getField(), param.getValue()));
        }

        return params;
    }

    private void addFacetFields(SearchRequest request, Map<String, String> params) {
        request.getFacetFieldsList().forEach(facetField -> {
            params.put("facet.field", facetField.getField());
            if (facetField.hasLimit()) {
                params.put("f." + facetField.getField() + ".facet.limit", String.valueOf(facetField.getLimit()));
            }
            if (facetField.hasMissing()) {
                params.put("f." + facetField.getField() + ".facet.missing", String.valueOf(facetField.getMissing()));
            }
            if (facetField.hasPrefix()) {
                params.put("f." + facetField.getField() + ".facet.prefix", facetField.getPrefix());
            }
        });
    }

    private void enableHighlighting(Map<String, String> params) {
        List<String> keywordFields = collectionConfig.getKeywordQueryFields();
        String fieldsToHighlight = String.join(",", keywordFields);

        params.put(HighlightParams.HIGHLIGHT, "true");
        params.put(HighlightParams.FIELDS, fieldsToHighlight);
        params.put(HighlightParams.SNIPPETS, "1");
        params.put(HighlightParams.FRAGSIZE, "100");
    }

    private void addKeywordParams(KeywordOptions keywordOptions, SearchRequest request, Map<String, String> params) {
        // Build the keyword search query using the keyword fields from the config
        List<String> keywordFields = collectionConfig.getKeywordQueryFields();
        String query = keywordFields.stream()
                .map(field -> field + ":(" + request.getQuery() + ")")
                .collect(Collectors.joining(" OR "));

        params.put("q", query);

        // Apply boost with semantic if needed
        if (keywordOptions.getBoostWithSemantic()) {
            // Retrieve the embedding for the query text
            List<Float> queryEmbedding = vectorService.getEmbeddingForText(request.getQuery());

            Map<String, VectorFieldInfo> vectorFields = collectionConfig.getVectorFieldsByName();
            // Loop over vector fields to apply boosts
            String boostQueries = vectorFields.values().stream()
                    .map(vectorFieldInfo -> {
                        String boostQuery = vectorService.buildVectorQueryForEmbedding(vectorFieldInfo, queryEmbedding, vectorFieldInfo.getK());
                        return boostQuery;
                    })
                    .collect(Collectors.joining(" "));
            params.put("bq", boostQueries);
        }
    }

    private void addSemanticParams(SemanticOptions semanticOptions, SearchRequest request, Map<String, String> params) {
        Map<String, VectorFieldInfo> vectorFieldInfoMap = collectionConfig.getVectorFieldsByName();

        // Log the requested vector fields
        log.info("Requested vector fields for semantic search: {}", semanticOptions.getVectorFieldsList());

        // Retrieve the embedding for the query text
        List<Float> queryEmbedding = vectorService.getEmbeddingForText(request.getQuery());
        log.debug("Retrieved query embedding: {}", queryEmbedding);

        int topK = semanticOptions.getTopK() != 0 ? semanticOptions.getTopK() : 10;  // Default to 10 if not specified
        log.debug("Using topK: {}", topK);

        // Determine which vector fields to use
        List<VectorFieldInfo> vectorFieldsToUse;
        if (semanticOptions.getVectorFieldsList().isEmpty()) {
            // Use all vector fields
            vectorFieldsToUse = new ArrayList<>(vectorFieldInfoMap.values());
            log.info("No vector fields specified. Using all configured vector fields.");
        } else {
            // Use specified vector fields
            vectorFieldsToUse = semanticOptions.getVectorFieldsList().stream()
                    .map(fieldName -> {
                        VectorFieldInfo info = vectorFieldInfoMap.get(fieldName);
                        if (info == null) {
                            log.error("VectorFieldInfo not found for field: {}", fieldName);
                            throw new IllegalArgumentException("Vector field not found: " + fieldName);
                        }
                        return info;
                    })
                    .collect(Collectors.toList());
            log.info("Using specified vector fields: {}", semanticOptions.getVectorFieldsList());
        }

        // Log the mapped VectorFieldInfo objects
        log.info("Mapped VectorFieldInfo objects: {}", vectorFieldsToUse);

        // Build queries for each vector field
        String combinedQuery = vectorFieldsToUse.stream()
                .map(vectorFieldInfo -> {
                    String vectorQuery = vectorService.buildVectorQueryForEmbedding(vectorFieldInfo, queryEmbedding, topK);
                    log.debug("Built vector query for field '{}': {}", vectorFieldInfo.getVectorFieldName(), vectorQuery);
                    return vectorQuery;
                })
                .collect(Collectors.joining(" OR "));

        log.debug("Combined vector query: {}", combinedQuery);
        params.put("q", combinedQuery);

        // Handle similarity options (if present)
        SimilarityOptions similarity = semanticOptions.hasSimilarity() ? semanticOptions.getSimilarity() : SimilarityOptions.getDefaultInstance();

        if (similarity.hasMinReturn()) {
            params.put("minReturn", String.valueOf(similarity.getMinReturn()));
            log.debug("Set minReturn to {}", similarity.getMinReturn());
        } else {
            // Default minReturn value
            params.put("minReturn", "1");
            log.debug("Set default minReturn to 1");
        }

        if (similarity.hasMinTraverse()) {
            params.put("minTraverse", String.valueOf(similarity.getMinTraverse()));
            log.debug("Set minTraverse to {}", similarity.getMinTraverse());
        } else {
            // Default minTraverse value
            params.put("minTraverse", "-Infinity");
            log.debug("Set default minTraverse to -Infinity");
        }

        // Pre-filters
        similarity.getPreFilterList().forEach(filter -> {
            String fq = filter.getField() + ":" + filter.getValue();
            params.put("fq", fq);
            log.debug("Added pre-filter: {}", fq);
        });
    }

    private SearchResponse parseSolrResponse(QueryResponse solrResponse, SearchRequest request) {
        SearchResponse.Builder responseBuilder = SearchResponse.newBuilder();

        // Map Solr documents to gRPC response
        solrResponse.getResults().forEach(doc -> {
            SearchResult.Builder resultBuilder = SearchResult.newBuilder()
                    .setId(doc.getFieldValue("id").toString());

            // Dynamically add fields to the result
            for (String field : collectionConfig.getKeywordQueryFields()) {
                if (doc.getFieldValue(field) != null) {
                    resultBuilder.putFields(field, doc.getFieldValue(field).toString());
                }
            }

            // Add matched snippets (semantic or keyword)
            if (request.getStrategy().hasSemantic()) {
                resultBuilder.setSnippet(getMatchedSemanticSnippet(doc));
            } else if (request.getStrategy().hasKeyword()) {
                String highlightSnippet = getHighlightSnippet(doc, solrResponse);
                resultBuilder.setSnippet(highlightSnippet != null ? highlightSnippet : "");
            }

            responseBuilder.addResults(resultBuilder.build());
        });

        // Handle facets in response
        if (solrResponse.getFacetFields() != null) {
            for (FacetField facet : solrResponse.getFacetFields()) {
                facet.getValues().forEach(value -> {
                    FacetResult facetResult = FacetResult.newBuilder()
                            .setFacet(value.getName())
                            .setFacetCount(value.getCount())
                            .build();
                    responseBuilder.putFacets(facet.getName(), facetResult);
                });
            }
        }

        // Total results and query time
        responseBuilder.setTotalResults(solrResponse.getResults().getNumFound());
        responseBuilder.setQTime(solrResponse.getQTime());

        // Add timestamp
        responseBuilder.setTimeOfSearch(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build());

        return responseBuilder.build();
    }

    private String getHighlightSnippet(SolrDocument doc, QueryResponse solrResponse) {
        Map<String, Map<String, List<String>>> highlighting = solrResponse.getHighlighting();
        if (highlighting != null) {
            Map<String, List<String>> docHighlight = highlighting.get(doc.getFieldValue("id"));
            if (docHighlight != null) {
                // Iterate over keyword query fields to find the first highlight
                for (String field : collectionConfig.getKeywordQueryFields()) {
                    if (docHighlight.containsKey(field)) {
                        return docHighlight.get(field).get(0);  // Get the first highlighted snippet
                    }
                }
            }
        }
        return null;
    }

    private String getMatchedSemanticSnippet(SolrDocument doc) {
        // Assuming 'matchedSnippet' is the field that contains the matched text for semantic search
        return doc.getFieldValue("matchedSnippet") != null ? doc.getFieldValue("matchedSnippet").toString() : "";
    }

    private void addFacetRanges(SearchRequest request, Map<String, String> params) {
        request.getFacetRangesList().forEach(facetRange -> {
            params.put("facet.range", facetRange.getField());

            if (facetRange.hasStart()) {
                params.put("f." + facetRange.getField() + ".facet.range.start", facetRange.getStart());
            }
            if (facetRange.hasEnd()) {
                params.put("f." + facetRange.getField() + ".facet.range.end", facetRange.getEnd());
            }
            if (facetRange.hasGap()) {
                params.put("f." + facetRange.getField() + ".facet.range.gap", facetRange.getGap());
            }
            if (facetRange.hasHardend()) {
                params.put("f." + facetRange.getField() + ".facet.range.hardend", String.valueOf(facetRange.getHardend()));
            }
            if (facetRange.hasOther()) {
                params.put("f." + facetRange.getField() + ".facet.range.other", facetRange.getOther());
            }
        });
    }
}