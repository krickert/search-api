package com.krickert.search.api.grpc.mapper.query;

import com.krickert.search.api.HighlightOptions;
import com.krickert.search.api.SearchRequest;
import jakarta.inject.Singleton;
import org.apache.solr.common.params.HighlightParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.micronaut.core.util.StringUtils.isNotEmpty;

@Singleton
public class HighlighterQueryBuilder {
    private static final Logger log = LoggerFactory.getLogger(HighlighterQueryBuilder.class);

    public void enableHighlighting(SearchRequest request, Map<String, List<String>> params) {
        HighlightOptions highlight = request.hasHighlightOptions() ? request.getHighlightOptions() : HighlightOptions.getDefaultInstance();

        params.put(HighlightParams.HIGHLIGHT, Collections.singletonList("true"));

        if (!highlight.getFieldsList().isEmpty()) {
            String fieldsToHighlight = String.join(",", highlight.getFieldsList());
            params.put(HighlightParams.FIELDS, Collections.singletonList(fieldsToHighlight));
        } else {
            // Default fields to highlight if none specified
            params.put(HighlightParams.FIELDS, Collections.singletonList("title,body"));
        }

        params.put(HighlightParams.SIMPLE_PRE, Collections.singletonList(
                isNotEmpty(highlight.getPreTag()) ? highlight.getPreTag() : "<em>"
        ));

        params.put(HighlightParams.SIMPLE_POST, Collections.singletonList(
                isNotEmpty(highlight.getPostTag()) ? highlight.getPostTag() : "</em>"
        ));

        params.put(HighlightParams.SNIPPETS, Collections.singletonList(
                highlight.getSnippetCount() > 0 ? String.valueOf(highlight.getSnippetCount()) : "1"
        ));

        params.put(HighlightParams.FRAGSIZE, Collections.singletonList(
                highlight.getSnippetSize() > 0 ? String.valueOf(highlight.getSnippetSize()) : "100"
        ));

        // Handle semantic-specific highlighting if applicable
        if (highlight.getSemanticHighlight()) {
            // Implement semantic highlighting logic as needed
            // This might involve highlighting entire chunks instead of snippets
            // Depending on your Solr schema and data structure
            log.debug("Semantic highlighting enabled.");
        }

        log.debug("Highlighting parameters set: {}", params);
    }
}
