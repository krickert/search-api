package com.krickert.search.api.grpc.mapper.query;

import com.krickert.search.api.*;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Singleton
public class FacetQueryBuilder {
    private static final Logger log = LoggerFactory.getLogger(FacetQueryBuilder.class);

    public void addUnifiedFacets(SearchRequest request, Map<String, List<String>> params) {
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
}
