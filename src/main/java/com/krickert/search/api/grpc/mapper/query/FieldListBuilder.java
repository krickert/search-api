package com.krickert.search.api.grpc.mapper.query;

import com.krickert.search.api.FieldList;
import com.krickert.search.api.SearchRequest;
import com.krickert.search.api.config.SearchApiConfig;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.micronaut.core.util.CollectionUtils.isNotEmpty;

@Singleton
public class FieldListBuilder {
    private static final Logger log = LoggerFactory.getLogger(FieldListBuilder.class);

    private final SearchApiConfig searchApiConfig;

    public FieldListBuilder(SearchApiConfig searchApiConfig) {
        this.searchApiConfig = checkNotNull(searchApiConfig);
    }

    public String handleFieldList(SearchRequest request, Map<String, List<String>> params) {
        String fl;
        FieldList fieldList = request.getFieldList();

        // Add inclusion fields
        Set<String> flParts = new HashSet<>(fieldList.getInclusionFieldsList());
        fieldList.getExclusionFieldsList().forEach(flParts::remove);
        flParts.addAll(searchApiConfig.getSolr().getDefaultSearch().getIncludeFields());
        flParts.removeAll(searchApiConfig.getSolr().getDefaultSearch().getExcludeFields());
        if (isNotEmpty(flParts)) {
            fl = String.join(",", flParts);
        } else {
            fl = "*,score"; //return all fields since nothing was requested
        }
        params.put("fl", Collections.singletonList(fl));
        log.debug("Set 'fl' parameter to: {}", fl);

        return fl;
    }
}
