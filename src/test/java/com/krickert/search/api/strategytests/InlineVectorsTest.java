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
                        .setOperator(LogicalOperator.OR) // Logical operator can be OR or AND based on requirements
                        .addStrategies(SearchStrategy.newBuilder()
                                .setType(StrategyType.KEYWORD)
                                .setKeyword(KeywordOptions.newBuilder() // No semantic boost for pure keyword search
                                        .build())
                                .build())
                        .build())
                .build();

        SearchResponse keywordResponse = searchServiceStub.search(keywordSearchRequest);
        validateAndLogResponse("Keyword Search Results", keywordResponse);

        // Test 2: Semantic Search on title-vector
        SearchRequest semanticTitleSearchRequest = SearchRequest.newBuilder()
                .setQuery(queryText)
                .setNumResults(30)
                .setStrategy(SearchStrategyOptions.newBuilder()
                        .setOperator(LogicalOperator.OR)
                        .addStrategies(SearchStrategy.newBuilder()
                                .setType(StrategyType.SEMANTIC)
                                .setSemantic(SemanticOptions.newBuilder()
                                        .setTopK(30)
                                        .addVectorFields("title-vector")
                                        .build())
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
                        .setOperator(LogicalOperator.OR)
                        .addStrategies(SearchStrategy.newBuilder()
                                .setType(StrategyType.SEMANTIC)
                                .setSemantic(SemanticOptions.newBuilder()
                                        .setTopK(30)
                                        .addVectorFields("body-vector")
                                        .build())
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
                        .setOperator(LogicalOperator.OR)
                        .addStrategies(SearchStrategy.newBuilder()
                                .setType(StrategyType.KEYWORD)
                                .setKeyword(KeywordOptions.newBuilder() // Enable semantic boosting
                                        .build())
                                .setBoost(1.5f) // Example boost factor
                                .build())
                        .addStrategies(SearchStrategy.newBuilder()
                                .setType(StrategyType.SEMANTIC)
                                .setSemantic(SemanticOptions.newBuilder()
                                        .setTopK(30)
                                        .addVectorFields("title-vector") // You can choose relevant vector fields
                                        .build())
                                .setBoost(1.2f) // Additional boost factor if needed
                                .build())
                        .build())
                .build();

        SearchResponse boostedResponse = searchServiceStub.search(boostedSearchRequest);
        validateAndLogResponse("Boosted Keyword Search Results", boostedResponse);
    }

}
