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
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.grpc.server.GrpcEmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = {"test-inline"})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class InlineVectorsTest extends AbstractSolrTest {

    private static final Logger log = LoggerFactory.getLogger(InlineVectorsTest.class);

    @Inject
    ApplicationContext context;

    @Inject
    ProtobufToSolrDocument protobufToSolrDocument;

    private EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingClient;
    private SearchServiceGrpc.SearchServiceBlockingStub searchServiceStub;

    // Define the vectorizer container as static to ensure it's started before tests
    @Container
    public static GenericContainer<?> vectorizerContainer = new GenericContainer<>(DockerImageName.parse("krickert/vectorizer:1.0-SNAPSHOT"))
            .withExposedPorts(50401)
            .withStartupTimeout(Duration.ofMinutes(2));

    private static final String VECTORS_CACHE_FILE = "src/test/resources/vectors-cache.json";

    @Value("${search-test.use-cached-vectors:false}")
    private boolean useCachedVectors;

    // Static block to start the vectorizer container and set the system property before Micronaut initializes
    static {
        vectorizerContainer.start();
        String host = vectorizerContainer.getHost();
        int port = vectorizerContainer.getMappedPort(50401);
        String vectorizerUrl = "http://" + host + ":" + port;
        System.setProperty("search-api.vector-grpc-channel", vectorizerUrl);
        System.setProperty("search-api.solr.url", "http://" + AbstractSolrTest.solrContainer.getHost() + ":" + AbstractSolrTest.solrContainer.getMappedPort(8983) + "/solr");
        log.info("Set system property 'search-api.vector-grpc-channel' to {}", vectorizerUrl);
        log.info("Set system property 'search-api.solr.url' to {}", System.getProperty("search-api.solr.url"));
    }

    @BeforeAll
    public void beforeAll() throws Exception {
        log.info("useCachedVectors is set to {}", useCachedVectors);

        if (!useCachedVectors) {
            // Initialize the gRPC embedding client
            ManagedChannel embeddingChannel = ManagedChannelBuilder.forAddress(vectorizerContainer.getHost(), vectorizerContainer.getMappedPort(50401))
                    .usePlaintext()
                    .build();
            embeddingClient = EmbeddingServiceGrpc.newBlockingStub(embeddingChannel);
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

        // Call parent setup method to initialize Solr
        super.setUp();
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
    private Map<String, Map<String, List<Float>>> loadVectorsCache() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        Path cachePath = Paths.get(VECTORS_CACHE_FILE);
        Map<String, Map<String, List<Float>>> vectorsCache;

        if (Files.exists(cachePath)) {
            vectorsCache = objectMapper.readValue(cachePath.toFile(),
                    new TypeReference<Map<String, Map<String, List<Float>>>>() {});
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
    private void saveVectorsCache(Map<String, Map<String, List<Float>>> vectorsCache) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        Path cachePath = Paths.get(VECTORS_CACHE_FILE);
        Files.createDirectories(cachePath.getParent()); // Ensure parent directories exist
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(cachePath.toFile(), vectorsCache);
        log.info("Saved vectors cache to {}", VECTORS_CACHE_FILE);
    }

    /**
     * Validates and logs the search response.
     */
    private void validateAndLogResponse(String testDescription, SearchResponse response) throws IOException {
        // Validate the response
        assertNotNull(response, testDescription + " response should not be null");
        assertNotNull(response.getResultsList(), testDescription + " results list should not be null");
        assertFalse(response.getResultsList().isEmpty(), testDescription + " results should not be empty");

        // Log results
        log.info("==== {} ====", testDescription);
        response.getResultsList().forEach(result -> {
            log.info("Found document ID: {}, Snippet: {}", result.getId(), StringUtils.truncate(result.getSnippet(), 100));
        });

        // Optionally, print the entire response in JSON format for debugging
        String jsonResponse = JsonFormat.printer().includingDefaultValueFields().print(response);
        log.debug("{} Response:\n{}", testDescription, jsonResponse);
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