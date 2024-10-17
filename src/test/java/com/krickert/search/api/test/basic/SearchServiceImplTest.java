package com.krickert.search.api.test.basic;

import com.google.protobuf.*;
import com.krickert.search.api.*;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.config.SearchApiConfig;
import com.krickert.search.api.grpc.SearchServiceImpl;
import com.krickert.search.api.grpc.mapper.query.SolrQueryBuilder;
import com.krickert.search.api.grpc.mapper.query.SolrQueryData;
import com.krickert.search.api.grpc.mapper.response.FacetProcessor;
import com.krickert.search.api.grpc.mapper.response.ResponseMapper;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class SearchServiceImplTest {

    private SolrService solrService;
    private SolrQueryBuilder solrQueryBuilder;
    private FacetProcessor facetProcessor;
    private ResponseMapper responseMapper;
    private SearchServiceImpl searchService;

    @BeforeEach
    void setUp() {
        // Mock dependencies
        solrService = mock(SolrService.class);
        solrQueryBuilder = mock(SolrQueryBuilder.class);
        facetProcessor = mock(FacetProcessor.class);
        responseMapper = mock(ResponseMapper.class);

        // Configure SearchApiConfig with necessary defaults and vector fields
        CollectionConfig collectionConfig = new CollectionConfig(Collections.emptyMap());
        collectionConfig.setCollectionName("test-collection");
        collectionConfig.setKeywordQueryFields(Arrays.asList("title", "description"));
        collectionConfig.setDefaultFields(Arrays.asList("id", "title", "description"));

        // Configure default search settings
        SearchApiConfig.SolrConfig solrConfig = new SearchApiConfig.SolrConfig(collectionConfig);
        SearchApiConfig.SolrConfig.DefaultSearch defaultSearch = new SearchApiConfig.SolrConfig.DefaultSearch();
        solrConfig.setDefaultSearch(defaultSearch);
        solrConfig.getDefaultSearch().setSort("score desc");
        solrConfig.getDefaultSearch().setRows(10);

        SearchApiConfig searchApiConfig = new SearchApiConfig();
        searchApiConfig.setSolr(solrConfig);

        // Initialize the SearchServiceImpl with mocked dependencies
        searchService = new SearchServiceImpl(solrService, searchApiConfig, solrQueryBuilder, responseMapper);
    }

    @Test
    void testBasicSearchWithDefaultFields() throws SolrServerException, IOException {
        // Prepare mock SolrResponse
        QueryResponse mockResponse = mock(QueryResponse.class);

        // Create a sample SolrDocument
        SolrDocument doc = new SolrDocument();
        doc.addField("id", "1");
        doc.addField("title", "Test Title");
        doc.addField("description", "Test Description");

        // Create a SolrDocumentList containing the sample document
        SolrDocumentList solrDocumentList = new SolrDocumentList();
        solrDocumentList.add(doc);
        solrDocumentList.setNumFound(1); // Set number of found documents

        // Configure the mockResponse to return the SolrDocumentList and query time
        when(mockResponse.getResults()).thenReturn(solrDocumentList);
        when(mockResponse.getQTime()).thenReturn(100);

        // Configure the solrService to return the mockResponse when queried
        when(solrService.query(anyString(), any())).thenReturn(mockResponse);

        // Mock SolrQueryBuilder to return expected SolrQueryData
        Map<String, List<String>> expectedParams = new HashMap<>();
        expectedParams.put("q", Collections.singletonList("test query"));
        expectedParams.put("start", Collections.singletonList("0"));
        expectedParams.put("rows", Collections.singletonList("10"));
        expectedParams.put("fl", Collections.singletonList("id,title,description"));

        SolrQueryData solrQueryData = new SolrQueryData(expectedParams);
        when(solrQueryBuilder.buildSolrQueryParams(any())).thenReturn(solrQueryData);

        // Mock FacetProcessor to return empty facets (no facets in this basic test)
        when(facetProcessor.processFacets(any())).thenReturn(new HashMap<>());

        // Prepare a mock SearchResponse to be returned by ResponseMapper
        SearchResult.Builder resultBuilder = SearchResult.newBuilder().setId("1");
        Struct.Builder fieldsBuilder = Struct.newBuilder();
        fieldsBuilder.putFields("title", convertToValue("Test Title"));
        fieldsBuilder.putFields("description", convertToValue("Test Description"));
        resultBuilder.setFields(fieldsBuilder.build());

        SearchResponse mockSearchResponse = SearchResponse.newBuilder()
                .setTotalResults(1)
                .setQTime(100)
                .setTimeOfSearch(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build())
                .addResults(resultBuilder.build())
                .build();

        // Configure the ResponseMapper to return the mockSearchResponse
        when(responseMapper.mapToSearchResponse(any(), any())).thenReturn(mockSearchResponse);

        // Create a search request with only query text
        SearchRequest request = SearchRequest.newBuilder()
                .setQuery("test query")
                .build();

        // Create a mock StreamObserver to capture the response
        @SuppressWarnings("unchecked")
        StreamObserver<SearchResponse> responseObserver = mock(StreamObserver.class);

        // Execute the search
        searchService.search(request, responseObserver);

        // Capture the response sent to the StreamObserver
        ArgumentCaptor<SearchResponse> responseCaptor = ArgumentCaptor.forClass(SearchResponse.class);
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        // Retrieve the captured response
        SearchResponse response = responseCaptor.getValue();

        // Perform assertions to validate the response
        assertNotNull(response, "Response should not be null");
        assertEquals(1, response.getTotalResults(), "Total results should be 1");
        assertEquals(100, response.getQTime(), "Query time should be 100 ms");
        assertEquals(1, response.getResultsCount(), "Results count should be 1");

        SearchResult result = response.getResults(0);
        assertEquals("1", result.getId(), "Document ID should be '1'");
        assertEquals("Test Title", result.getFields().getFieldsMap().get("title").getStringValue(), "Title field should match");
        assertEquals("Test Description", result.getFields().getFieldsMap().get("description").getStringValue(), "Description field should match");
        assertTrue(result.getSnippet().isEmpty(), "Snippet should be empty");
    }

    // Helper function to convert an Object to a google.protobuf.Value
    private Value convertToValue(Object value) {
        Value.Builder valueBuilder = Value.newBuilder();

        if (value == null) {
            valueBuilder.setNullValue(NullValue.NULL_VALUE);
        } else if (value instanceof String) {
            valueBuilder.setStringValue((String) value);
        } else if (value instanceof Number) {
            valueBuilder.setNumberValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            valueBuilder.setBoolValue((Boolean) value);
        } else if (value instanceof Map) {
            // Convert Map to Struct
            Struct.Builder structBuilder = Struct.newBuilder();
            @SuppressWarnings("unchecked")
            Map<String, Object> mapValue = (Map<String, Object>) value;
            mapValue.forEach((key, mapVal) -> structBuilder.putFields(key, convertToValue(mapVal)));
            valueBuilder.setStructValue(structBuilder);
        } else if (value instanceof List) {
            // Convert List to ListValue
            ListValue.Builder listBuilder = ListValue.newBuilder();
            @SuppressWarnings("unchecked")
            List<Object> listValue = (List<Object>) value;
            listValue.forEach(item -> listBuilder.addValues(convertToValue(item)));
            valueBuilder.setListValue(listBuilder);
        } else {
            // Fallback to String representation if type is not recognized
            valueBuilder.setStringValue(value.toString());
        }

        return valueBuilder.build();
    }
}