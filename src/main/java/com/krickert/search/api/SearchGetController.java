package com.krickert.search.api;

import com.google.protobuf.util.JsonFormat;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import jakarta.inject.Inject;

@Controller("/api/search")
public class SearchGetController {
    private final SearchServiceGrpc.SearchServiceBlockingStub searchService;

    @Inject
    public SearchGetController(SearchServiceGrpc.SearchServiceBlockingStub searchService) {
        this.searchService = searchService;
    }

    @Get(produces = MediaType.APPLICATION_JSON)
    public String searchViaParams(
            @QueryValue String query,
            @QueryValue(defaultValue = "0") int start,
            @QueryValue(defaultValue = "10") int numResults,
            @Nullable @QueryValue(defaultValue = "") String sortField,
            @Nullable @QueryValue(defaultValue = "") String sortType,
            @Nullable @QueryValue(defaultValue = "") String sortOrder,
            @Nullable @QueryValue(defaultValue = "") String facetField,
            @Nullable @QueryValue(defaultValue = "") String facetRangeStart,
            @Nullable @QueryValue(defaultValue = "") String facetRangeEnd,
            @Nullable @QueryValue(defaultValue = "") String facetRangeGap,
            @Nullable @QueryValue(defaultValue = "") String highlightField,
            @Nullable @QueryValue(defaultValue = "") String highlightPreTag,
            @Nullable @QueryValue(defaultValue = "") String highlightPostTag,
            @QueryValue(defaultValue = "0") int snippetCount,
            @QueryValue(defaultValue = "0") int snippetSize) {
        try {
            // Build SearchRequest from query parameters
            SearchRequest.Builder searchRequestBuilder = SearchRequest.newBuilder();

            // Set the required query parameter
            searchRequestBuilder.setQuery(query);
            searchRequestBuilder.setStart(start).setNumResults(numResults);

            // Optional Sort options
            if (sortField != null && !sortField.isEmpty() ||
                    sortType != null && !sortType.isEmpty() ||
                    sortOrder != null && !sortOrder.isEmpty()) {
                SortOptions.Builder sortOptionsBuilder = SortOptions.newBuilder();
                if (sortField != null && !sortField.isEmpty()) {
                    sortOptionsBuilder.setSortField(sortField);
                }
                if (sortType != null && !sortType.isEmpty()) {
                    sortOptionsBuilder.setSortType(SortType.valueOf(sortType.toUpperCase()));
                }
                if (sortOrder != null && !sortOrder.isEmpty()) {
                    sortOptionsBuilder.setSortOrder(SortOrder.valueOf(sortOrder.toUpperCase()));
                }
                searchRequestBuilder.setSort(sortOptionsBuilder);
            }

            // Optional Facet options
            if (facetField != null && !facetField.isEmpty()) {
                FacetField facetFieldOption = FacetField.newBuilder().setField(facetField).build();
                FacetRequest facetRequest = FacetRequest.newBuilder().setFacetField(facetFieldOption).build();
                searchRequestBuilder.addFacetRequests(facetRequest);
            }
            if (facetRangeStart != null && !facetRangeStart.isEmpty() &&
                    facetRangeEnd != null && !facetRangeEnd.isEmpty() &&
                    facetRangeGap != null && !facetRangeGap.isEmpty()) {
                FacetRange facetRange = FacetRange.newBuilder()
                        .setField(facetField)
                        .setStart(facetRangeStart)
                        .setEnd(facetRangeEnd)
                        .setGap(facetRangeGap)
                        .build();
                FacetRequest facetRequest = FacetRequest.newBuilder().setFacetRange(facetRange).build();
                searchRequestBuilder.addFacetRequests(facetRequest);
            }

            // Optional Highlight options
            if (highlightField != null && !highlightField.isEmpty() ||
                    highlightPreTag != null && !highlightPreTag.isEmpty() ||
                    highlightPostTag != null && !highlightPostTag.isEmpty() ||
                    snippetCount > 0 || snippetSize > 0) {
                HighlightOptions.Builder highlightOptionsBuilder = HighlightOptions.newBuilder();
                if (highlightField != null && !highlightField.isEmpty()) {
                    highlightOptionsBuilder.addFields(highlightField);
                }
                if (highlightPreTag != null && !highlightPreTag.isEmpty()) {
                    highlightOptionsBuilder.setPreTag(highlightPreTag);
                }
                if (highlightPostTag != null && !highlightPostTag.isEmpty()) {
                    highlightOptionsBuilder.setPostTag(highlightPostTag);
                }
                if (snippetCount > 0) {
                    highlightOptionsBuilder.setSnippetCount(snippetCount);
                }
                if (snippetSize > 0) {
                    highlightOptionsBuilder.setSnippetSize(snippetSize);
                }
                searchRequestBuilder.setHighlightOptions(highlightOptionsBuilder);
            }

            SearchRequest searchRequest = searchRequestBuilder.build();

            // Call gRPC service
            SearchResponse response = searchService.search(searchRequest);

            // Convert SearchResponse protobuf to JSON
            return JsonFormat.printer().includingDefaultValueFields().print(response);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to process search request", e);
        }
    }
}