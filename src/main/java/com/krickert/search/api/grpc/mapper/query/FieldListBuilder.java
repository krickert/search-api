package com.krickert.search.api.grpc.mapper.query;

import com.krickert.search.api.FieldList;
import com.krickert.search.api.SearchRequest;
import com.krickert.search.api.config.SearchApiConfig;
import io.micronaut.core.util.CollectionUtils;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class FieldListBuilder {
    private static final Logger log = LoggerFactory.getLogger(FieldListBuilder.class);

    private final SearchApiConfig searchApiConfig;

    public FieldListBuilder(SearchApiConfig searchApiConfig) {
        this.searchApiConfig = checkNotNull(searchApiConfig);
    }

    public String handleFieldList(SearchRequest request, Map<String, List<String>> params) {
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

            // Detect conflicts (fields in both inclusion and exclusion)
            Set<String> includedSet = new HashSet<>(inclusionFields);
            Set<String> excludedSet = new HashSet<>(exclusionFields);
            includedSet.retainAll(excludedSet);
            if (!includedSet.isEmpty()) {
                log.warn("Fields {} are both included and excluded. They will be excluded.", includedSet);
                // Remove conflicting fields from inclusion
                flParts.removeAll(includedSet);
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
}
