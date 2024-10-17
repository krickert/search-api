package com.krickert.search.api.test.base;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.api.*;
import com.krickert.search.api.grpc.client.VectorService;
import com.krickert.search.api.solr.EmbeddedInfinispanCache;
import com.krickert.search.api.solr.ProtobufToSolrDocument;
import com.krickert.search.api.test.TestContainersManager;
import com.krickert.search.service.PipeServiceGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.Map;

import static com.krickert.search.api.solr.SolrHelper.addDenseVectorField;
import static com.krickert.search.api.solr.SolrHelper.addField;
import static io.micronaut.core.util.StringUtils.isNotEmpty;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseSearchApiTest extends BaseSolrTest implements TestPropertyProvider {

    protected ApplicationContext context;

    protected ProtobufToSolrDocument protobufToSolrDocument = new ProtobufToSolrDocument();
    protected VectorService vectorService = null;

    @Override
    public @NonNull Map<String, String> getProperties() {
        return Map.of(
                "search-api.solr.collection-config.collection-name", getCollectionName(),
                "search-api.vector-default.vector-grpc-channel", TestContainersManager.getVectorizerUrl(),
                "search-api.solr.url", TestContainersManager.getSolrBaseUrl()
        );
    }

    @Inject
    protected SearchServiceGrpc.SearchServiceBlockingStub searchServiceStub;

    @Bean
    SearchServiceGrpc.SearchServiceBlockingStub serviceBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME)
            ManagedChannel channel) {
        return SearchServiceGrpc.newBlockingStub(
                channel
        );
    }

    @BeforeEach
    public void setUp() {
        log.info("Setting up base api");
        super.setUp();
        this.vectorService = new VectorService(embeddingServiceStub, new EmbeddedInfinispanCache("./vector-cache"));
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
            solrClient = TestContainersManager.createSolrClient();
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
