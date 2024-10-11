package com.krickert.search.api.test.base;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.api.FacetResult;
import com.krickert.search.api.FacetResults;
import com.krickert.search.api.SearchResponse;
import com.krickert.search.api.SearchResult;
import com.krickert.search.api.grpc.client.VectorService;
import com.krickert.search.api.solr.ProtobufToSolrDocument;
import com.krickert.search.api.solr.SolrService;
import com.krickert.search.api.test.TestContainersManager;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Inject;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static com.krickert.search.api.solr.SolrHelper.addDenseVectorField;
import static com.krickert.search.api.solr.SolrHelper.addField;
import static io.micronaut.core.util.StringUtils.isNotEmpty;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public abstract class BaseSearchApiTest extends BaseSolrTest {

    protected ApplicationContext context;

    protected ProtobufToSolrDocument protobufToSolrDocument = new ProtobufToSolrDocument();
    protected VectorService vectorService = null;

    @BeforeEach
    public void setUp() {
        super.setUp();
        // Initialize Micronaut application context
        context = ApplicationContext.run();
        context.getEnvironment()
                .addPropertySource(
                        PropertySource.of(
                                "test", Collections.singletonMap(
                                        "search-api.solr.url",
                                        TestContainersManager.getSolrBaseUrl())
                        ));
        context.getEnvironment()
                .addPropertySource(
                        PropertySource.of(
                                "test", Collections.singletonMap(
                                        "search-api.vector-default.vector-grpc-channel",
                                        TestContainersManager.getVectorizerUrl())
                        ));
        //search-api.solr.collection-config.collection-name

        context.getEnvironment()
                .addPropertySource(
                        PropertySource.of(
                                "test", Collections.singletonMap(
                                        "search-api.solr.collection-config.collection-name",
                                        getChunkCollectionName())
                        ));
        // Inject or configure VectorService with the appropriate gRPC stub
        VectorService vectorService = context.getBean(VectorService.class);
        vectorService.updateEmbeddingClient(embeddingServiceStub);
        this.vectorService = vectorService;
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Shutdown Micronaut application context
        if (context != null) {
            context.close();
        }
        super.tearDown();
    }

    /**
     * Validates and logs the search response.
     *
     * @param testDescription The description of the test being logged.
     * @param response The search response to validate and log.
     * @throws IOException if the response cannot be serialized to JSON for logging.
     */
    protected void validateAndLogResponse(String testDescription, SearchResponse response) throws IOException, InvalidProtocolBufferException {
        // Validate the response
        assertNotNull(response, testDescription + " response should not be null");
        assertNotNull(response.getResultsList(), testDescription + " results list should not be null");
        assertFalse(response.getResultsList().isEmpty(), testDescription + " results should not be empty");

        log.info("=== {} ===", testDescription);
        log.info("Total Results: {}", response.getTotalResults());
        log.info("Query Time: {} ms", response.getQTime());

        for (SearchResult result : response.getResultsList()) {
            log.debug("Document ID: {}", result.getId());
            log.debug("Snippet: {}", result.getSnippet());
            log.debug("Matched Text: {}", result.getMatchedTextList());
            log.debug("Fields: {}", result.getFields());
        }

        // Optionally, validate facets if applicable
        if (!response.getFacetsMap().isEmpty()) {
            log.debug("Facets:");
            for (Map.Entry<String, FacetResults> facetEntry : response.getFacetsMap().entrySet()) {
                log.debug("Facet Field: {}", facetEntry.getKey());
                for (FacetResult facetResult : facetEntry.getValue().getResultsList()) {
                    log.debug("  Facet: {}, Count: {}", facetResult.getFacet(), facetResult.getFacetCount());
                }
            }
        }

        // Optionally, print the entire response in JSON format for debugging
        String jsonResponse = JsonFormat.printer().includingDefaultValueFields().print(response);
        log.debug("{} Response:\n{}", testDescription, jsonResponse);
        log.info("=======================");
    }

    @Override
    protected void setupCollections() {
        try {
            String collectionName = getCollectionName();
            CollectionAdminRequest.Create createCollection = CollectionAdminRequest.createCollection(collectionName, 1, 1);
            createCollection.process(solrClient);
             // Define schema for the collection
            addField(solrClient, "title", "string", false, collectionName);
            addField(solrClient, "body", "text_general", false, collectionName);
            addField(solrClient, "type", "string", false, collectionName);
            addField(solrClient,"body_paragraphs", "text_general", true, collectionName);
            addField(solrClient,"chunk-number", "pint", false, collectionName);
            addField(solrClient, "chunk", "text_general", false, collectionName);
            addField(solrClient, "parent-id", "string", false, collectionName);
            addField(solrClient, "_nest_parent_", "string", false, collectionName);
            addDenseVectorField(solrClient, "title-vector", 384, collectionName);
            addDenseVectorField(solrClient, "body-vector", 384, collectionName);

            String chunkCollection = getChunkCollectionName();
            if (isNotEmpty(chunkCollection)) {
                log.info("Creating temporary vector collection: {}", chunkCollection);
                CollectionAdminRequest.Create createVectorCollection = CollectionAdminRequest.createCollection(chunkCollection, 1, 1);
                createVectorCollection.process(solrClient);
                addField(solrClient, "parent-id", "string", false, chunkCollection);
                addField(solrClient, "chunk-number", "pint", false, chunkCollection);
                addField(solrClient, "chunk", "text_general", false, chunkCollection);
                addDenseVectorField(solrClient, "chunk-vector", 384, chunkCollection);
            }
        } catch (SolrServerException | IOException e) {
            throw new RuntimeException("Failed to set up collections: " + e.getMessage(), e);
        }
    }

    @Override
    protected void deleteCollections() {
        try {
            log.info("deleting collections");
            // Delete collection after each test
            log.info("Deleting temporary collection: {}", getCollectionName());
            CollectionAdminRequest.Delete deleteCollection = CollectionAdminRequest.deleteCollection(getCollectionName());
            deleteCollection.process(solrClient);
            if (isNotEmpty(getChunkCollectionName())) {
                log.info("Deleting temporary vector collection: {}", getChunkCollectionName());
                CollectionAdminRequest.Delete deleteVectorCollection = CollectionAdminRequest.deleteCollection(getChunkCollectionName());
                deleteVectorCollection.process(solrClient);
            }
        } catch (SolrServerException | IOException e) {
            throw new RuntimeException("Failed to delete collections: " + e.getMessage(), e);
        }
    }
}
