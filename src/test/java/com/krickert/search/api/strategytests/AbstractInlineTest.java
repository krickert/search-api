package com.krickert.search.api.strategytests;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.api.*;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.config.VectorFieldInfo;
import com.krickert.search.api.solr.ProtobufToSolrDocument;
import com.krickert.search.api.solr.SolrHelper;
import com.krickert.search.model.pipe.PipeDocument;
import com.krickert.search.model.test.util.TestDataHelper;
import com.krickert.search.service.EmbeddingServiceGrpc;
import com.krickert.search.service.EmbeddingsVectorReply;
import com.krickert.search.service.EmbeddingsVectorRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.grpc.server.GrpcEmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@MicronautTest(environments = {"test-inline"})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractInlineTest extends AbstractSolrTest {

    protected static final Logger log = LoggerFactory.getLogger(AbstractInlineTest.class);

    @Inject
    protected ApplicationContext context;

    @Inject
    protected ProtobufToSolrDocument protobufToSolrDocument;

    protected EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingClient;
    protected SearchServiceGrpc.SearchServiceBlockingStub searchServiceStub;

    // Define the vectorizer container as static to ensure it's started before tests
    @Container
    public static GenericContainer<?> vectorizerContainer = new GenericContainer<>(DockerImageName.parse("krickert/vectorizer:1.0-SNAPSHOT"))
            .withExposedPorts(50401)
            .withStartupTimeout(Duration.ofMinutes(2));

    private static final String VECTORS_CACHE_FILE = "src/test/resources/vectors-cache.json";

    @Value("${search-test.use-cached-vectors:false}")
    protected boolean useCachedVectors;

    // Static block to start the vectorizer container and set the system property before Micronaut initializes
    static {
        vectorizerContainer.start();
        String host = vectorizerContainer.getHost();
        int port = vectorizerContainer.getMappedPort(50401);
        String vectorizerUrl = "http://" + host + ":" + port;
        System.setProperty("search-api.vector-default.vector-grpc-channel", vectorizerUrl);
        System.setProperty("search-api.solr.url", "http://" + AbstractSolrTest.solrContainer.getHost() + ":" + AbstractSolrTest.solrContainer.getMappedPort(8983) + "/solr");
        log.info("Set system property 'search-api.vector-grpc-channel' to {}", vectorizerUrl);
        log.info("Set system property 'search-api.solr.url' to {}", System.getProperty("search-api.solr.url"));
    }


    @BeforeAll
    public void beforeAll() throws Exception {
        log.info("useCachedVectors is set to {}", useCachedVectors);

        // Wait for vectorizer container to be ready
        if (!useCachedVectors) {
            try {
                ManagedChannel embeddingChannel = ManagedChannelBuilder
                        .forAddress(vectorizerContainer.getHost(), vectorizerContainer.getMappedPort(50401))
                        .usePlaintext()
                        .build();
                embeddingClient = EmbeddingServiceGrpc.newBlockingStub(embeddingChannel);
                log.info("Embedding gRPC client initialized successfully.");
            } catch (Exception e) {
                log.error("Failed to initialize embedding gRPC client: {}", e.getMessage(), e);
                throw e;
            }
        } else {
            log.info("Using cached vectors. Vectorizer service will not be started.");
        }

        // Initialize gRPC search client
        try {
            GrpcEmbeddedServer grpcServer = context.getBean(GrpcEmbeddedServer.class);
            int grpcPort = grpcServer.getPort();
            ManagedChannel searchServiceChannel = ManagedChannelBuilder
                    .forAddress("localhost", grpcPort)
                    .usePlaintext()
                    .build();
            searchServiceStub = SearchServiceGrpc.newBlockingStub(searchServiceChannel);
            log.info("Search service gRPC client initialized successfully.");
        } catch (Exception e) {
            log.error("Failed to initialize search service gRPC client: {}", e.getMessage(), e);
            throw e;
        }

        // Call parent setup method to initialize Solr
        super.setUp();
        log.info("Solr setup completed.");
    }


    @AfterAll
    public void afterAll() throws Exception {
        if (!useCachedVectors && vectorizerContainer != null) {
            vectorizerContainer.stop();
            log.info("Vectorizer container stopped.");
        }
        super.tearDown();
    }

    @Override
    protected void setupSolrCollectionsAndSchema() throws Exception {
        // Create the collection
        createCollection(DEFAULT_COLLECTION, 1, 1);

        // Define the schema for the collection
        SolrHelper.addField(solrClient, "title", "text_general", false, DEFAULT_COLLECTION);
        SolrHelper.addField(solrClient, "body", "text_general", false, DEFAULT_COLLECTION);
        SolrHelper.addField(solrClient, "type", "string", false, DEFAULT_COLLECTION);
        SolrHelper.addDenseVectorField(solrClient, "title-vector", 384, DEFAULT_COLLECTION);
        SolrHelper.addDenseVectorField(solrClient, "body-vector", 384, DEFAULT_COLLECTION);

        // Log the vector fields configured
        Map<String, VectorFieldInfo> vectorFields = context.getBean(CollectionConfig.class).getVectorFields();
        log.info("Configured vector fields: {}", vectorFields.keySet());
    }

    @Override
    protected void seedCollection() throws Exception {
        Map<String, Map<String, List<Float>>> vectorsCache = loadVectorsCache();

        Collection<PipeDocument> docs = TestDataHelper.getFewHunderedPipeDocuments();
        List<SolrInputDocument> solrDocs = Collections.synchronizedList(new ArrayList<>());

        AtomicBoolean cacheUpdated = new AtomicBoolean(false);

        // Use a custom ForkJoinPool to limit parallelism if needed
        ForkJoinPool customThreadPool = new ForkJoinPool(8); // Adjust parallelism as needed

        try {
            customThreadPool.submit(() ->
                    docs.parallelStream().forEach(pipeDocument -> {
                        try {
                            SolrInputDocument inputDocument = protobufToSolrDocument.convertProtobufToSolrDocument(pipeDocument);

                            String docId = (String) inputDocument.getFieldValue("id");

                            Map<String, List<Float>> docVectors;

                            synchronized (vectorsCache) { // Synchronize access to vectorsCache
                                if (vectorsCache.containsKey(docId)) {
                                    docVectors = vectorsCache.get(docId);
                                    log.debug("Loaded vectors from cache for docId: {}", docId);
                                } else {
                                    if (useCachedVectors) {
                                        throw new RuntimeException("Vectors cache does not contain vectors for docId: " + docId);
                                    }

                                    // Limit body to first 1500 characters
                                    String bodyText = pipeDocument.getBody();
                                    if (bodyText.length() > 1500) {
                                        bodyText = bodyText.substring(0, 1500);
                                    }
                                    inputDocument.setField("body", bodyText);

                                    // Generate embeddings
                                    EmbeddingsVectorReply titleVectorReply = embeddingClient.createEmbeddingsVector(
                                            EmbeddingsVectorRequest.newBuilder().setText(pipeDocument.getTitle()).build());
                                    EmbeddingsVectorReply bodyVectorReply = embeddingClient.createEmbeddingsVector(
                                            EmbeddingsVectorRequest.newBuilder().setText(bodyText).build());

                                    List<Float> titleVector = titleVectorReply.getEmbeddingsList();
                                    List<Float> bodyVector = bodyVectorReply.getEmbeddingsList();

                                    docVectors = new HashMap<>();
                                    docVectors.put("title-vector", titleVector);
                                    docVectors.put("body-vector", bodyVector);

                                    vectorsCache.put(docId, docVectors);
                                    cacheUpdated.set(true);
                                    log.debug("Generated and cached vectors for docId: {}", docId);
                                }
                            }

                            // Add vectors to the document
                            inputDocument.addField("title-vector", docVectors.get("title-vector"));
                            inputDocument.addField("body-vector", docVectors.get("body-vector"));

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

        if (cacheUpdated.get()) {
            // Save vectors cache to file
            saveVectorsCache(vectorsCache);
            log.info("Vectors cache updated and saved with {} entries.", vectorsCache.size());
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
     * Loads the vectors cache from the JSON file.
     * If the file does not exist, it creates a new empty cache.
     */
    protected Map<String, Map<String, List<Float>>> loadVectorsCache() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        Path cachePath = Paths.get(VECTORS_CACHE_FILE);
        Map<String, Map<String, List<Float>>> vectorsCache;

        if (Files.exists(cachePath)) {
            vectorsCache = objectMapper.readValue(cachePath.toFile(),
                    new TypeReference<>() {});
            log.info("Loaded vectors cache from {}", VECTORS_CACHE_FILE);
        } else {
            vectorsCache = new HashMap<>();
            log.info("Vectors cache file not found at {}. A new one will be created.", VECTORS_CACHE_FILE);
        }

        return vectorsCache;
    }

    /**
     * Saves the vectors cache to the JSON file.
     */
    protected void saveVectorsCache(Map<String, Map<String, List<Float>>> vectorsCache) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        Path cachePath = Paths.get(VECTORS_CACHE_FILE);
        Files.createDirectories(cachePath.getParent()); // Ensure parent directories exist
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(cachePath.toFile(), vectorsCache);
        log.info("Saved vectors cache to {}", VECTORS_CACHE_FILE);
    }

    /**
     * Validates and logs the search response.
     */
    protected void validateAndLogResponse(String testDescription, SearchResponse response) throws IOException {
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
            log.debug("Fields: {}", result.getFieldsMap());
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

    @BeforeEach
    public void checkSolrConnection() {
        try {
            solrClient.ping("dummy");
        } catch (SolrServerException | IOException e) {
            log.debug("exception thrown", e);
            solrClient = new Http2SolrClient.Builder(solrBaseUrl).build();
        }

    }


}