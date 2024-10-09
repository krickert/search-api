package com.krickert.search.api.strategytests;

import com.krickert.search.api.*;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@MicronautTest(environments = {"test-inline"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Advanced Inline Vectors Search Tests")
public class AdvancedInlineVectorsTest extends AbstractInlineTest {

    private static final Logger log = LoggerFactory.getLogger(AdvancedInlineVectorsTest.class);
    @BeforeEach
    public void checkSolrConnection() {
        try {
            solrClient.ping("dummy");
        } catch (SolrServerException | IOException e) {
            log.debug("exception thrown", e);
            solrClient = new Http2SolrClient.Builder(solrBaseUrl).build();
        }

    }
    @Test
    @DisplayName("Combined Semantic and Keyword Search with Facets")
    public void testCombinedSemanticAndKeywordSearch() throws Exception {
        // The target query text
        String queryText = "data analysis and machine learning";

        // Combined Semantic and Keyword Search
        SearchRequest combinedSearchRequest = SearchRequest.newBuilder()
                .setQuery(queryText)
                .setNumResults(50)
                .setStrategy(SearchStrategyOptions.newBuilder()
                        .setOperator(LogicalOperator.AND) // Combine strategies with AND
                        .addStrategies(SearchStrategy.newBuilder()
                                .setType(StrategyType.SEMANTIC) // First strategy: SEMANTIC
                                .setSemantic(SemanticOptions.newBuilder()
                                        .setTopK(50)
                                        .addVectorFields("title-vector")
                                        .addVectorFields("body-vector")
                                        .build())
                                .setBoost(1.2f) // Optional: Boost factor for SEMANTIC
                                .build())
                        .addStrategies(SearchStrategy.newBuilder()
                                .setType(StrategyType.KEYWORD) // Second strategy: KEYWORD
                                .setKeyword(KeywordOptions.newBuilder()
                                        .build())
                                .setBoost(1.5f) // Optional: Boost factor for KEYWORD
                                .build())
                        .build())
                .addFilterQueries("document_type:ARTICLE") // Apply filter query
                .setSort(SortOptions.newBuilder()
                        .setSortType(SortType.SCORE) // Sort by score
                        .setSortOrder(SortOrder.DESC) // Descending order
                        .build())
                .setHighlightOptions(HighlightOptions.newBuilder()
                        .addFields("title") // Fields to highlight
                        .addFields("body")
                        .setPreTag("<strong>") // Pre-tag for highlights
                        .setPostTag("</strong>") // Post-tag for highlights
                        .setSnippetCount(2) // Number of snippets per field
                        .setSnippetSize(150) // Size of each snippet
                        .setSemanticHighlight(true) // Enable semantic-specific highlighting
                        .build())
                .setFieldList(FieldList.newBuilder()
                        .addInclusionFields("id") // Example: Include 'id' field
                        .addInclusionFields("title") // Include 'title' field
                        .addExclusionFields("body") // Example: Exclude 'body' field if needed
                        .build())
                .build();

        // Execute the search request using the gRPC stub
        SearchResponse combinedResponse = searchServiceStub.search(combinedSearchRequest);

        // Validate and log the response
        validateAndLogResponse("Combined Semantic and Keyword Search Results", combinedResponse);

        // Additional assertions can be added here based on expected outcomes
    }




}
