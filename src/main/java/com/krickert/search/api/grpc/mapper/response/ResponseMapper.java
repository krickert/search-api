package com.krickert.search.api.grpc.mapper.response;

import com.google.protobuf.*;
import com.krickert.search.api.FacetResults;
import com.krickert.search.api.SearchRequest;
import com.krickert.search.api.SearchResponse;
import com.krickert.search.api.SearchResult;
import jakarta.inject.Singleton;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.krickert.search.api.grpc.mapper.response.ToProtobuf.convertToValue;

@Singleton
public class ResponseMapper {
    private static final Logger log = LoggerFactory.getLogger(ResponseMapper.class);

    private final FacetProcessor facetProcessor;

    public ResponseMapper(FacetProcessor facetProcessor) {
        this.facetProcessor = facetProcessor;
    }

    public SearchResponse mapToSearchResponse(QueryResponse solrResponse, SearchRequest request) {
        log.debug("mapping search response for search {}", request);

        SearchResponse.Builder responseBuilder = SearchResponse.newBuilder();

        /// Map Solr documents to SearchResult
        for (SolrDocument doc : solrResponse.getResults()) {
            SearchResult.Builder resultBuilder = SearchResult.newBuilder();

            // Set document ID
            Object idObj = doc.getFieldValue("id");
            if (idObj != null) {
                resultBuilder.setId(idObj.toString());
            }

            // Create a Struct builder for the fields
            Struct.Builder structBuilder = Struct.newBuilder();

            // Dynamically add fields based on 'fl' parameter
            for (String field : doc.getFieldNames()) {
                Object value = doc.getFieldValue(field);
                if (value != null) {
                    if (request.hasFieldList() && !request.getFieldList().getExclusionFieldsList().contains(field)) {
                        // Convert the value to a Value type and add it to the Struct
                        structBuilder.putFields(field, convertToValue(value));
                    }
                }
            }
            // Set the fields Struct in the result builder
            resultBuilder.setFields(structBuilder.build());

            // Add snippets (highlighting)
            if (solrResponse.getHighlighting() != null && solrResponse.getHighlighting().size() > 1 && idObj != null) {
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
                includedFields.add(field.trim());
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