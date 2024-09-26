package com.krickert.search.api.solr;

import com.google.protobuf.Timestamp;
import com.krickert.search.api.*;
import com.krickert.search.api.config.SearchApiConfig;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import org.apache.solr.client.solrj.response.QueryResponse;

import java.util.HashMap;
import java.util.Map;

@GrpcService
public class SearchServiceImpl extends SearchServiceGrpc.SearchServiceImplBase {

    private final String collectionName;

    private final SolrService solrService;

    @Inject
    public SearchServiceImpl(SolrService solrService, SearchApiConfig config) {
        this.solrService = solrService;
        this.collectionName = config.getSolr().getCollectionConfig().getCollectionName();

    }

    @Override
    public void search(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
        try {
            // Create Solr query parameters
            Map<String, String> queryParams = buildSolrQueryParams(request);
            // Execute Solr search
            QueryResponse solrResponse = solrService.query(collectionName, queryParams);
            // Parse the Solr response
            SearchResponse searchResponse = parseSolrResponse(solrResponse);
            // Send response
            responseObserver.onNext(searchResponse);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    private Map<String, String> buildSolrQueryParams(SearchRequest request) {
        Map<String, String> params = new HashMap<>();
        params.put("q", request.getQuery());

        // Pagination
        params.put("start", String.valueOf(request.getStart()));
        params.put("rows", String.valueOf(request.getNumResults()));

        // Filters
        request.getFilterQueriesList().forEach(filter -> params.put("fq", filter));

        // Sorting
        if (request.hasSort()) {
            SortOptions sort = request.getSort();
            String sortField = sort.getSortField();
            String sortOrder = sort.getSortOrder().name().toLowerCase();
            params.put("sort", sortField + " " + sortOrder);
        }

        // Facets
        request.getFacetFieldsList().forEach(facetField ->
                params.put("facet.field", facetField.getField())
        );

        // Range Facets
        request.getFacetRangesList().forEach(facetRange -> {
            params.put("facet.range", facetRange.getField());
            if (facetRange.hasStart())
                params.put("f." + facetRange.getField() + ".facet.range.start", facetRange.getStart());
            if (facetRange.hasEnd()) params.put("f." + facetRange.getField() + ".facet.range.end", facetRange.getEnd());
            if (facetRange.hasGap()) params.put("f." + facetRange.getField() + ".facet.range.gap", facetRange.getGap());
        });

        // Join conditions (example)
        if (request.getStrategy() == SearchStrategy.SEMANTIC) {
            params.put("fq", "{!join from=childField to=parentField}childQuery:(" + request.getQuery() + ")");
        }

        return params;
    }

    private SearchResponse parseSolrResponse(QueryResponse solrResponse) {
        SearchResponse.Builder responseBuilder = SearchResponse.newBuilder();

        // Map Solr documents to gRPC response
        solrResponse.getResults().forEach(solrDocument -> {
            SearchResult.Builder resultBuilder = SearchResult.newBuilder()
                    .setId(solrDocument.getFieldValue("id").toString())
                    .setTitle(solrDocument.getFieldValue("title").toString())
                    .setSnippet(solrDocument.getFieldValue("snippet").toString());

            responseBuilder.addResults(resultBuilder.build());
        });

        // Total results and query time
        responseBuilder.setTotalResults(solrResponse.getResults().getNumFound())
                .setQTime(solrResponse.getQTime());

        // Add timestamp
        responseBuilder.setTimeOfSearch(Timestamp.newBuilder().build());

        return responseBuilder.build();
    }
}