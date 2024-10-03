package com.krickert.search.api.grpc;

import com.google.protobuf.Timestamp;
import com.krickert.search.api.*;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.config.SearchApiConfig;
import com.krickert.search.api.grpc.client.VectorService;
import com.krickert.search.api.grpc.mapper.response.FacetProcessor;
import com.krickert.search.api.grpc.mapper.response.ResponseMapper;
import com.krickert.search.api.grpc.mapper.query.SolrQueryBuilder;
import com.krickert.search.api.grpc.mapper.query.SolrQueryData;
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

class SearchServiceImplTest {

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

        SolrQueryData solrQueryData = new SolrQueryData(expectedParams, "id,title,description");
        when(solrQueryBuilder.buildSolrQueryParams(any())).thenReturn(solrQueryData);

        // Mock FacetProcessor to return empty facets (no facets in this basic test)
        when(facetProcessor.processFacets(any())).thenReturn(new HashMap<>());

        // Prepare a mock SearchResponse to be returned by ResponseMapper
        SearchResponse mockSearchResponse = SearchResponse.newBuilder()
                .setTotalResults(1)
                .setQTime(100)
                .setTimeOfSearch(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build())
                .addResults(SearchResult.newBuilder()
                        .setId("1")
                        .putFields("title", "Test Title")
                        .putFields("description", "Test Description")
                        .build())
                .build();

        // Configure the ResponseMapper to return the mockSearchResponse
        when(responseMapper.mapToSearchResponse(any(), any(), any())).thenReturn(mockSearchResponse);

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

        // Debugging output (optional)
        System.out.println("Total Results: " + response.getTotalResults());
        System.out.println("Query Time: " + response.getQTime());
        System.out.println("Results Count: " + response.getResultsCount());

        // Perform assertions to validate the response
        assertNotNull(response, "Response should not be null");
        assertEquals(1, response.getTotalResults(), "Total results should be 1");
        assertEquals(100, response.getQTime(), "Query time should be 100 ms");
        assertEquals(1, response.getResultsCount(), "Results count should be 1");

        SearchResult result = response.getResults(0);
        assertEquals("1", result.getId(), "Document ID should be '1'");
        assertEquals("Test Title", result.getFieldsMap().get("title"), "Title field should match");
        assertEquals("Test Description", result.getFieldsMap().get("description"), "Description field should match");
        assertTrue(result.getSnippet().isEmpty(), "Snippet should be empty");
    }

    @Test
    void testSearchWithFacets() throws SolrServerException, IOException {
        // Prepare mock SolrResponse with facets
        QueryResponse mockResponse = mock(QueryResponse.class);

        // Create sample SolrDocuments
        SolrDocument doc1 = new SolrDocument();
        doc1.addField("id", "1");
        doc1.addField("title", "Machine Learning Basics");
        doc1.addField("description", "An introduction to machine learning.");

        SolrDocument doc2 = new SolrDocument();
        doc2.addField("id", "2");
        doc2.addField("title", "Advanced Machine Learning");
        doc2.addField("description", "Deep dive into machine learning algorithms.");

        // Create a SolrDocumentList containing the sample documents
        SolrDocumentList solrDocumentList = new SolrDocumentList();
        solrDocumentList.add(doc1);
        solrDocumentList.add(doc2);
        solrDocumentList.setNumFound(2); // Set number of found documents

        // Configure the mockResponse to return the SolrDocumentList and query time
        when(mockResponse.getResults()).thenReturn(solrDocumentList);
        when(mockResponse.getQTime()).thenReturn(150);

        // Configure the solrService to return the mockResponse when queried
        when(solrService.query(anyString(), any())).thenReturn(mockResponse);

        // Mock SolrQueryBuilder to return expected SolrQueryData with facets
        Map<String, List<String>> expectedParams = new HashMap<>();
        expectedParams.put("q", Collections.singletonList("machine learning"));
        expectedParams.put("start", Collections.singletonList("0"));
        expectedParams.put("rows", Collections.singletonList("10"));
        expectedParams.put("fl", Collections.singletonList("id,title,description"));
        expectedParams.put("facet.field", Arrays.asList("type", "category"));
        expectedParams.put("f.type.facet.limit", Collections.singletonList("10"));
        expectedParams.put("f.type.facet.missing", Collections.singletonList("true"));

        SolrQueryData solrQueryData = new SolrQueryData(expectedParams, "id,title,description");
        when(solrQueryBuilder.buildSolrQueryParams(any())).thenReturn(solrQueryData);

        // Mock FacetProcessor to return processed facets
        Map<String, FacetResults> processedFacets = new HashMap<>();
        processedFacets.put("type", FacetResults.newBuilder()
                .addResults(FacetResult.newBuilder().setFacet("book").setFacetCount(2).build())
                .build());
        processedFacets.put("category", FacetResults.newBuilder()
                .addResults(FacetResult.newBuilder().setFacet("education").setFacetCount(2).build())
                .build());
        when(facetProcessor.processFacets(any())).thenReturn(processedFacets);

        // Prepare a mock SearchResponse to be returned by ResponseMapper
        SearchResponse mockSearchResponse = SearchResponse.newBuilder()
                .setTotalResults(2)
                .setQTime(150)
                .setTimeOfSearch(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build())
                .addResults(SearchResult.newBuilder()
                        .setId("1")
                        .putFields("title", "Machine Learning Basics")
                        .putFields("description", "An introduction to machine learning.")
                        .build())
                .addResults(SearchResult.newBuilder()
                        .setId("2")
                        .putFields("title", "Advanced Machine Learning")
                        .putFields("description", "Deep dive into machine learning algorithms.")
                        .build())
                .putAllFacets(processedFacets)
                .build();

        // Configure the ResponseMapper to return the mockSearchResponse
        when(responseMapper.mapToSearchResponse(any(), any(), any())).thenReturn(mockSearchResponse);

        // Create a search request with query text and facets
        SearchRequest request = SearchRequest.newBuilder()
                .setQuery("machine learning")
                .addFacetRequests(FacetRequest.newBuilder()
                        .setFacetField(FacetField.newBuilder()
                                .setField("type")
                                .setLimit(10)
                                .setMissing(true)
                                .build())
                        .build())
                .addFacetRequests(FacetRequest.newBuilder()
                        .setFacetField(FacetField.newBuilder()
                                .setField("category")
                                .setLimit(10)
                                .build())
                        .build())
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

        // Debugging output (optional)
        System.out.println("Total Results: " + response.getTotalResults());
        System.out.println("Query Time: " + response.getQTime());
        System.out.println("Results Count: " + response.getResultsCount());
        System.out.println("Facets: " + response.getFacetsMap());

        // Perform assertions to validate the response
        assertNotNull(response, "Response should not be null");
        assertEquals(2, response.getTotalResults(), "Total results should be 2");
        assertEquals(150, response.getQTime(), "Query time should be 150 ms");
        assertEquals(2, response.getResultsCount(), "Results count should be 2");

        // Validate first search result
        SearchResult result1 = response.getResults(0);
        assertEquals("1", result1.getId(), "Document ID should be '1'");
        assertEquals("Machine Learning Basics", result1.getFieldsMap().get("title"), "Title field should match");
        assertEquals("An introduction to machine learning.", result1.getFieldsMap().get("description"), "Description field should match");
        assertTrue(result1.getSnippet().isEmpty(), "Snippet should be empty");

        // Validate second search result
        SearchResult result2 = response.getResults(1);
        assertEquals("2", result2.getId(), "Document ID should be '2'");
        assertEquals("Advanced Machine Learning", result2.getFieldsMap().get("title"), "Title field should match");
        assertEquals("Deep dive into machine learning algorithms.", result2.getFieldsMap().get("description"), "Description field should match");
        assertTrue(result2.getSnippet().isEmpty(), "Snippet should be empty");

        // Validate facets
        assertTrue(response.getFacetsMap().containsKey("type"), "Facet 'type' should be present.");
        assertTrue(response.getFacetsMap().containsKey("category"), "Facet 'category' should be present.");

        FacetResults typeFacets = response.getFacetsMap().get("type");
        assertEquals(1, typeFacets.getResultsCount(), "Facet 'type' should have 1 result.");
        assertEquals("book", typeFacets.getResults(0).getFacet(), "Facet 'type' value should be 'book'");
        assertEquals(2, typeFacets.getResults(0).getFacetCount(), "Facet 'type' count should be 2");

        FacetResults categoryFacets = response.getFacetsMap().get("category");
        assertEquals(1, categoryFacets.getResultsCount(), "Facet 'category' should have 1 result.");
        assertEquals("education", categoryFacets.getResults(0).getFacet(), "Facet 'category' value should be 'education'");
        assertEquals(2, categoryFacets.getResults(0).getFacetCount(), "Facet 'category' count should be 2");
    }

    // Additional test cases can be added here
}