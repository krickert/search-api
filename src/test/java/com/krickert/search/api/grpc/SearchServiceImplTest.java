package com.krickert.search.api.grpc;

import com.krickert.search.api.*;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.config.SearchApiConfig;
import com.krickert.search.api.solr.SolrService;
import io.grpc.stub.StreamObserver;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SearchServiceImplTest {

    private SolrService solrService;
    private VectorService vectorService;
    private SearchApiConfig searchApiConfig;
    private SearchServiceImpl searchService;

    @BeforeEach
    void setUp() {
        solrService = mock(SolrService.class);
        vectorService = mock(VectorService.class);
        searchApiConfig = new SearchApiConfig();

        // Configure SearchApiConfig with necessary defaults and vector fields
        CollectionConfig collectionConfig = new CollectionConfig(Collections.emptyMap());
        collectionConfig.setCollectionName("test-collection");
        collectionConfig.setKeywordQueryFields(Arrays.asList("title", "description"));
        collectionConfig.setDefaultFields(Arrays.asList("id", "title", "description"));

        searchApiConfig.setSolr(new SearchApiConfig.SolrConfig(collectionConfig));
        SearchApiConfig.SolrConfig.DefaultSearch defaultSearch = new SearchApiConfig.SolrConfig.DefaultSearch();
        searchApiConfig.getSolr().setDefaultSearch(defaultSearch);
        searchApiConfig.getSolr().getDefaultSearch().setSort("score desc");
        searchApiConfig.getSolr().getDefaultSearch().setRows(10);

        searchService = new SearchServiceImpl(solrService, vectorService, searchApiConfig);
    }

    @Test
    void testBasicSearchWithDefaultFields() throws SolrServerException, IOException {
        // Prepare mock SolrResponse
        QueryResponse mockResponse = mock(QueryResponse.class);

        SolrDocument doc = new SolrDocument();
        doc.addField("id", "1");
        doc.addField("title", "Test Title");
        doc.addField("description", "Test Description");

        SolrDocumentList solrDocumentList = new SolrDocumentList();
        solrDocumentList.add(doc);
        solrDocumentList.setNumFound(1); // Set number of found documents

        when(mockResponse.getResults()).thenReturn(solrDocumentList);
        // Ensure other appropriate responses matching expected return types
        when(mockResponse.getQTime()).thenReturn(100);
        when(solrService.query(anyString(), any())).thenReturn(mockResponse);

        // Create a search request with only query text
        SearchRequest request = SearchRequest.newBuilder()
                .setQuery("test query")
                .build();

        // Create a mock StreamObserver
        StreamObserver<SearchResponse> responseObserver = mock(StreamObserver.class);

        // Execute search
        searchService.search(request, responseObserver);

        // Capture the response
        ArgumentCaptor<SearchResponse> responseCaptor = ArgumentCaptor.forClass(SearchResponse.class);
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        SearchResponse response = responseCaptor.getValue();
        System.out.println("Total Results: " + response.getTotalResults()); // Debugging line
        System.out.println("Query Time: " + response.getQTime());           // Debugging line
        System.out.println("Results Count: " + response.getResultsCount()); // Debugging line

        // Perform assertions
        assertEquals(1, response.getTotalResults()); // Check if the total results are as expected
        assertEquals(100, response.getQTime()); // Check if the query time is as expected
        assertEquals(1, response.getResultsCount()); // Check if the results count is as expected

        SearchResult result = response.getResults(0);
        assertEquals("1", result.getId());
        assertEquals("Test Title", result.getFieldsMap().get("title"));
        assertEquals("Test Description", result.getFieldsMap().get("description"));
        assertTrue(result.getSnippet().isEmpty());
    }
    // Additional test cases can be added here
}