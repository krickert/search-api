package com.krickert.search.api.test.old.strategytests;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.api.*;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.config.VectorFieldInfo;
import com.krickert.search.api.grpc.client.VectorService;
import com.krickert.search.api.solr.ProtobufToSolrDocument;
import com.krickert.search.model.pipe.PipeDocument;
import com.krickert.search.model.test.util.TestDataHelper;
import com.krickert.search.service.EmbeddingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.grpc.server.GrpcEmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@MicronautTest(environments = {"test-inline"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractInlineTest extends AbstractSolrTest {

    protected static final Logger log = LoggerFactory.getLogger(AbstractInlineTest.class);

    @Inject
    protected ApplicationContext context;

    @Inject
    protected ProtobufToSolrDocument protobufToSolrDocument;

    @Inject
    protected VectorService vectorService; // Inject VectorService to ensure caching

    protected EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingClient;
    protected SearchServiceGrpc.SearchServiceBlockingStub searchServiceStub;

    public GenericContainer<?> vectorizerContainer;

    @Value("${search-test.use-cached-vectors:false}")
    protected boolean useCachedVectors;

    private ManagedChannel embeddingChannel;

    @BeforeAll
    public void beforeAll() throws Exception {
        log.info("useCachedVectors is set to {}", useCachedVectors);

        if (!useCachedVectors) {
            vectorizerContainer = new GenericContainer<>(DockerImageName.parse("krickert/vectorizer:1.0-SNAPSHOT"))
                    .withExposedPorts(50401)
                    .withStartupTimeout(Duration.ofMinutes(2));

            startVectorizerContainer();
        } else {
            log.info("Using cached vectors. Vectorizer service will not be started.");
        }

        // Initialize the gRPC search client
        GrpcEmbeddedServer grpcServer = context.getBean(GrpcEmbeddedServer.class);
        int grpcPort = grpcServer.getPort();

        ManagedChannel searchServiceChannel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        searchServiceStub = SearchServiceGrpc.newBlockingStub(searchServiceChannel);
//
//        // Call parent setup method to initialize Solr
//        super.setUp();
    }

    @AfterAll
    public void afterAll() throws Exception {
        // Close the embedding channel if it exists
        if (embeddingChannel != null && !embeddingChannel.isShutdown()) {
            embeddingChannel.shutdownNow();
            log.info("Embedding channel shut down.");
        }

        if (!useCachedVectors && vectorizerContainer != null && vectorizerContainer.isRunning()) {
            vectorizerContainer.stop();
            log.info("Vectorizer container stopped.");
        }
        super.tearDown();
    }

    @BeforeEach
    public void setUp() {
        startVectorizerContainer();
        // Reinitialize the embedding client
        startVectorService();
    }

    @AfterEach
    public void tearDown() {
        try {
            if (vectorizerContainer != null && vectorizerContainer.isRunning()) {
                vectorizerContainer.stop();
                log.info("Vectorizer container stopped.");
            }
        } catch (Exception e) {
            log.warn("Failed to stop vectorizer container properly.", e);
        }
    }

    private void startVectorizerContainer() {
        int retries = 5;
        int retryIntervalMillis = 2000; // Retry interval of 2 seconds

        for (int i = 0; i < retries; i++) {
            try {
                if (!vectorizerContainer.isRunning()) {
                    vectorizerContainer.start();
                    log.info("Vectorizer container started successfully.");
                }

                startVectorService();
                break;
            } catch (Exception e) {
                log.warn("Vectorizer container not ready or client connection failed. Attempt {} of {}.", i + 1, retries);
                if (i == retries - 1) {
                    throw new RuntimeException("Failed to start vectorizer container or reset client after retries", e);
                }
            }
            try {
                Thread.sleep(retryIntervalMillis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during vectorizer container startup", ie);
            }
        }
    }

    private void startVectorService() {
        // Initialize the embedding client with the updated vectorizer container port
        if (vectorizerContainer != null && vectorizerContainer.isRunning()) {
            int vectorizerPort = vectorizerContainer.getMappedPort(50401);
            embeddingChannel = ManagedChannelBuilder.forAddress("localhost", vectorizerPort)
                    .usePlaintext()
                    .build();
            embeddingClient = EmbeddingServiceGrpc.newBlockingStub(embeddingChannel);
            log.info("Vectorizer client connection reset successfully.");

            // Update VectorService with the new embedding client
            vectorService.updateEmbeddingClient(embeddingClient);
        }
    }

    @Override
    protected void setupSolrCollectionsAndSchema() throws Exception {
        // Define the schema for the collection

        // Log the vector fields configured
        Map<String, VectorFieldInfo> vectorFields = context.getBean(CollectionConfig.class).getVectorFields();
        log.info("Configured vector fields: {}", vectorFields.keySet());
    }

    @Override
    protected void seedCollection() throws Exception {
        Collection<PipeDocument> docs = TestDataHelper.getFewHunderedPipeDocuments();
        List<SolrInputDocument> solrDocs = Collections.synchronizedList(new ArrayList<>());
        // Use a custom ForkJoinPool to limit parallelism if needed
        ForkJoinPool customThreadPool = new ForkJoinPool(8); // Adjust parallelism as needed

        try {
            customThreadPool.submit(() ->
                    docs.parallelStream().forEach(pipeDocument -> {
                        try {
                            SolrInputDocument inputDocument = protobufToSolrDocument.convertProtobufToSolrDocument(pipeDocument);

                            String docId = (String) inputDocument.getFieldValue("id");

                            // Use VectorService to get embeddings (with caching enabled)
                            List<Float> titleVector = vectorService.getEmbeddingForText(pipeDocument.getTitle());
                            List<Float> bodyVector = vectorService.getEmbeddingForText(pipeDocument.getBody());

                            // Add vectors to the document
                            inputDocument.addField("title-vector", titleVector);
                            inputDocument.addField("body-vector", bodyVector);

                            inputDocument.addField("type", "document");

                            solrDocs.add(inputDocument);
                        } catch (Exception e) {
                            log.error("Error processing document with ID {}: {}", pipeDocument.getId(), e.getMessage());
                            throw new RuntimeException(e);
                        }
                    })
            ).get(); // Wait for completion
        } catch (ExecutionException | InterruptedException e) {
            log.error("Error during parallel document processing: {}", e.getMessage());
            throw e;
        } finally {
            customThreadPool.shutdown();
        }

        // Add documents to Solr
        try {
            solrClient.add(DEFAULT_COLLECTION, solrDocs);
            solrClient.commit(DEFAULT_COLLECTION);
            log.info("Added {} documents to Solr collection '{}'.", solrDocs.size(), DEFAULT_COLLECTION);
        } catch (Exception e) {
            log.error("Failed to add documents to Solr: {}", e.getMessage());
            throw new RuntimeException("Failed to add documents to Solr", e);
        }
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
}
