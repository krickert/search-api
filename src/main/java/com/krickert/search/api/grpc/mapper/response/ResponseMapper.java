package com.krickert.search.api.grpc.mapper.response;

import com.google.protobuf.Timestamp;
import com.krickert.search.api.*;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;
import java.util.*;

@Singleton
public class ResponseMapper {
    private static final Logger log = LoggerFactory.getLogger(ResponseMapper.class);

    private final FacetProcessor facetProcessor;

    public ResponseMapper(FacetProcessor facetProcessor) {
        this.facetProcessor = facetProcessor;
    }

    public SearchResponse mapToSearchResponse(QueryResponse solrResponse, SearchRequest request, String fl) {
        log.debug("mapping search response for search {}", request);

        SearchResponse.Builder responseBuilder = SearchResponse.newBuilder();

        // Determine the list of fields to include based on 'fl' parameter
        Set<String> includedFields = extractIncludedFields(fl);

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
                    String snippet = buildSnippet(highlighting);
                    resultBuilder.setSnippet(snippet);
                } else {
                    resultBuilder.setSnippet("");
                }
            }

            responseBuilder.addResults(resultBuilder.build());
        }

        // Handle facets
        Map<String, FacetResults> processedFacets = facetProcessor.processFacets(solrResponse);
        responseBuilder.putAllFacets(processedFacets);

        // Set total results and query time
        responseBuilder.setTotalResults(solrResponse.getResults().getNumFound());
        responseBuilder.setQTime(solrResponse.getQTime());

        // Set timestamp
        responseBuilder.setTimeOfSearch(Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
                .build());

        return responseBuilder.build();
    }

    private Set<String> extractIncludedFields(String fl) {
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
        return includedFields;
    }

    private String buildSnippet(Map<String, List<String>> highlighting) {
        List<String> snippets = new ArrayList<>();
        for (List<String> snippetList : highlighting.values()) {
            snippets.addAll(snippetList);
        }

        // Join snippets with a separator
        return String.join(" ... ", snippets);
    }
}