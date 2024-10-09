package com.krickert.search.api;

import com.google.gson.Gson;
import com.google.protobuf.util.JsonFormat;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

@Controller("/api/search")
public class SearchRestController {

    private final SearchServiceGrpc.SearchServiceBlockingStub searchService;

    @Inject
    public SearchRestController(@Named("searchServiceInternal") SearchServiceGrpc.SearchServiceBlockingStub searchService) {
        this.searchService = searchService;
    }

    @Post(consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public String search(@Body SearchRequestBean requestBean) {
        try {
            // Convert bean to SearchRequest protobuf
            SearchRequest.Builder searchRequestBuilder = SearchRequest.newBuilder();
            JsonFormat.parser().ignoringUnknownFields().merge(requestBean.toJson(), searchRequestBuilder);
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


    public static class SearchRequestBean {
        @NotBlank
        private String query;
        private int start = 0;
        private int numResults = 10;
        private SortOptionsBean sort;
        private List<FacetRequestBean> facetRequests;
        private HighlightOptionsBean highlightOptions;

        // Getters and setters

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public int getStart() {
            return start;
        }

        public void setStart(int start) {
            this.start = start;
        }

        public int getNumResults() {
            return numResults;
        }

        public void setNumResults(int numResults) {
            this.numResults = numResults;
        }

        public SortOptionsBean getSort() {
            return sort;
        }

        public void setSort(SortOptionsBean sort) {
            this.sort = sort;
        }

        public List<FacetRequestBean> getFacetRequests() {
            return facetRequests;
        }

        public void setFacetRequests(List<FacetRequestBean> facetRequests) {
            this.facetRequests = facetRequests;
        }

        public HighlightOptionsBean getHighlightOptions() {
            return highlightOptions;
        }

        public void setHighlightOptions(HighlightOptionsBean highlightOptions) {
            this.highlightOptions = highlightOptions;
        }

        public String toJson() {
            try {
                Gson gson = new Gson();
                return gson.toJson(this);
            } catch (Exception e) {
                throw new RuntimeException("Failed to convert bean to JSON", e);
            }
        }
    }

    public static class SortOptionsBean {
        private String sortType;
        private String sortOrder;
        private String sortField;

        // Getters and setters

        public String getSortType() {
            return sortType;
        }

        public void setSortType(String sortType) {
            this.sortType = sortType;
        }

        public String getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(String sortOrder) {
            this.sortOrder = sortOrder;
        }

        public String getSortField() {
            return sortField;
        }

        public void setSortField(String sortField) {
            this.sortField = sortField;
        }
    }
    public static class FacetRequestBean {
        private String facetField;
        private String facetRangeStart;
        private String facetRangeEnd;
        private String facetRangeGap;

        // Getters and setters

        public String getFacetField() {
            return facetField;
        }

        public void setFacetField(String facetField) {
            this.facetField = facetField;
        }

        public String getFacetRangeStart() {
            return facetRangeStart;
        }

        public void setFacetRangeStart(String facetRangeStart) {
            this.facetRangeStart = facetRangeStart;
        }

        public String getFacetRangeEnd() {
            return facetRangeEnd;
        }

        public void setFacetRangeEnd(String facetRangeEnd) {
            this.facetRangeEnd = facetRangeEnd;
        }

        public String getFacetRangeGap() {
            return facetRangeGap;
        }

        public void setFacetRangeGap(String facetRangeGap) {
            this.facetRangeGap = facetRangeGap;
        }
    }

    public static class HighlightOptionsBean {
        private List<String> fields;
        private String preTag;
        private String postTag;
        private int snippetCount;
        private int snippetSize;

        // Getters and setters

        public List<String> getFields() {
            return fields;
        }

        public void setFields(List<String> fields) {
            this.fields = fields;
        }

        public String getPreTag() {
            return preTag;
        }

        public void setPreTag(String preTag) {
            this.preTag = preTag;
        }

        public String getPostTag() {
            return postTag;
        }

        public void setPostTag(String postTag) {
            this.postTag = postTag;
        }

        public int getSnippetCount() {
            return snippetCount;
        }

        public void setSnippetCount(int snippetCount) {
            this.snippetCount = snippetCount;
        }

        public int getSnippetSize() {
            return snippetSize;
        }

        public void setSnippetSize(int snippetSize) {
            this.snippetSize = snippetSize;
        }
    }
}
