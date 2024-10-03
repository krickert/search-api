package com.krickert.search.api.grpc;

import com.krickert.search.api.FacetResult;
import com.krickert.search.api.FacetResults;
import com.krickert.search.api.SearchRequest;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.RangeFacet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class FacetProcessor {
    private static final Logger log = LoggerFactory.getLogger(FacetProcessor.class);

    public Map<String, FacetResults> processFacets(QueryResponse solrResponse, SearchRequest request) {
        Map<String, List<FacetResult>> facetsMap = new HashMap<>();

        // Handle Facet Fields
        if (solrResponse.getFacetFields() != null) {
            for (FacetField solrFacet : solrResponse.getFacetFields()) {
                List<FacetResult> facetResults = facetsMap.computeIfAbsent(solrFacet.getName(), k -> new ArrayList<>());
                if (solrFacet.getValues() != null) {
                    for (FacetField.Count count : solrFacet.getValues()) {
                        FacetResult facetResult = FacetResult.newBuilder()
                                .setFacet(count.getName())
                                .setFacetCount(count.getCount())
                                .build();
                        facetResults.add(facetResult);
                    }
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

        // Handle Facet Ranges
        if (solrResponse.getFacetRanges() != null) {
            for (@SuppressWarnings("rawtypes") RangeFacet rangeFacet : solrResponse.getFacetRanges()) {
                String rangeField = rangeFacet.getName();
                List<FacetResult> facetResults = facetsMap.computeIfAbsent(rangeField, k -> new ArrayList<>());
                if (rangeFacet.getCounts() != null) {
                    for (Object countObj : rangeFacet.getCounts()) {
                        RangeFacet.Count count = (RangeFacet.Count) countObj;
                        FacetResult facetResult = FacetResult.newBuilder()
                                .setFacet(count.getValue())
                                .setFacetCount(count.getCount())
                                .build();
                        facetResults.add(facetResult);
                    }
                }
            }
        }

        // Convert to FacetResults
        Map<String, FacetResults> processedFacets = new HashMap<>();
        for (Map.Entry<String, List<FacetResult>> entry : facetsMap.entrySet()) {
            FacetResults facetResults = FacetResults.newBuilder()
                    .addAllResults(entry.getValue())
                    .build();
            processedFacets.put(entry.getKey(), facetResults);
        }

        log.debug("Processed facets: {}", processedFacets);
        return processedFacets;
    }
}