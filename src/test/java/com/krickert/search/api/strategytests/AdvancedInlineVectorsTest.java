package com.krickert.search.api.strategytests;

import com.krickert.search.api.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Advanced Inline Vectors Search Tests")
public class AdvancedInlineVectorsTest extends AbstractInlineTest {

    private static final Logger log = LoggerFactory.getLogger(AdvancedInlineVectorsTest.class);

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
                        .setSemantic(SemanticOptions.newBuilder()
                                .setTopK(50)
                                .addVectorFields("title-vector")
                                .addVectorFields( "body-vector")
                                .build())
                        .setKeyword(KeywordOptions.newBuilder()
                                .setBoostWithSemantic(true)
                                .build())
                        .setOperator(LogicalOperator.AND)
                        .build())
                .addFilterQueries("type:document")
                .setSort(SortOptions.newBuilder()
                        .setSortType(SortType.SCORE)
                        .setSortOrder(SortOrder.DESC)
                        .build())
                .setHighlightOptions(HighlightOptions.newBuilder()
                        .addFields("title")
                        .addFields( "body")
                        .setPreTag("<strong>")
                        .setPostTag("</strong>")
                        .setSnippetCount(2)
                        .setSnippetSize(150)
                        .setSemanticHighlight(true)
                        .build())
                .build();

        SearchResponse combinedResponse = searchServiceStub.search(combinedSearchRequest);
        validateAndLogResponse("Combined Semantic and Keyword Search Results", combinedResponse);

        // Additional assertions can be added here based on expected outcomes
    }
}
