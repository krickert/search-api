package com.krickert.search.api.strategytests;

import com.krickert.search.api.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Inline Vectors Search Tests")
public class InlineVectorsTest extends AbstractInlineTest {

    private static final Logger log = LoggerFactory.getLogger(InlineVectorsTest.class);

    @BeforeEach
    public void setupTest() {
        // Any additional setup before each test can be added here
    }

    @AfterEach
    public void tearDownTest() {
        // Any cleanup after each test can be added here
    }

    @Test
    @DisplayName("Semantic and Keyword Search using New Search API")
    public void testSearchUsingSearchAPI() throws Exception {
        // The target query text
        String queryText = "maintaining computers in large organizations";

        // Test 1: Keyword Search
        SearchRequest keywordSearchRequest = SearchRequest.newBuilder()
                .setQuery(queryText)
                .setNumResults(30)
                .setStrategy(SearchStrategyOptions.newBuilder()
                        .setKeyword(KeywordOptions.newBuilder().setBoostWithSemantic(false).build())
                        .build())
                .build();

        SearchResponse keywordResponse = searchServiceStub.search(keywordSearchRequest);
        validateAndLogResponse("Keyword Search Results", keywordResponse);

        // Test 2: Semantic Search on title-vector
        SearchRequest semanticTitleSearchRequest = SearchRequest.newBuilder()
                .setQuery(queryText)
                .setNumResults(30)
                .setStrategy(SearchStrategyOptions.newBuilder()
                        .setSemantic(SemanticOptions.newBuilder()
                                .setTopK(30)
                                .addVectorFields("title-vector")
                                .build())
                        .build())
                .build();

        SearchResponse semanticTitleResponse = searchServiceStub.search(semanticTitleSearchRequest);
        validateAndLogResponse("Semantic Search on Title Results", semanticTitleResponse);

        // Test 3: Semantic Search on body-vector
        SearchRequest semanticBodySearchRequest = SearchRequest.newBuilder()
                .setQuery(queryText)
                .setNumResults(30)
                .setStrategy(SearchStrategyOptions.newBuilder()
                        .setSemantic(SemanticOptions.newBuilder()
                                .setTopK(30)
                                .addVectorFields("body-vector")
                                .build())
                        .build())
                .build();

        SearchResponse semanticBodyResponse = searchServiceStub.search(semanticBodySearchRequest);
        validateAndLogResponse("Semantic Search on Body Results", semanticBodyResponse);

        // Test 4: Keyword Search boosted with Semantic
        SearchRequest boostedSearchRequest = SearchRequest.newBuilder()
                .setQuery(queryText)
                .setNumResults(30)
                .setStrategy(SearchStrategyOptions.newBuilder()
                        .setKeyword(KeywordOptions.newBuilder()
                                .setBoostWithSemantic(true)
                                .build())
                        .build())
                .build();

        SearchResponse boostedResponse = searchServiceStub.search(boostedSearchRequest);
        validateAndLogResponse("Boosted Keyword Search Results", boostedResponse);
    }
}
