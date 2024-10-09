package com.krickert.search.api.solr;

import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static com.krickert.search.api.solr.SolrHelper.addDenseVectorField;
import static com.krickert.search.api.solr.SolrHelper.addField;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SolrTest {

    public static final String DEFAULT_COLLECTION = "documents";
    public static final String VECTOR_COLLECTION = "vector-documents";
    Logger log = org.slf4j.LoggerFactory.getLogger(SolrTest.class);

    @Container
    public static SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.7.0")) {
        @Override
        protected void configure() {
            this.addExposedPort(8983);
            String command = "solr -c -f";
            this.setCommand(command);
            this.waitStrategy =
                    new LogMessageWaitStrategy()
                            .withRegEx(".*Server Started.*")
                            .withStartupTimeout(Duration.of(180, ChronoUnit.SECONDS));
        }
    };

    protected static Http2SolrClient solrClient;
    protected static String solrBaseUrl = null;

    @BeforeAll
    public void setUp() throws Exception {
        solrContainer.start();
        log.info("Solr running on {}:{}", solrContainer.getHost(), solrContainer.getMappedPort(8983));

        solrBaseUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr";
        solrClient = new Http2SolrClient.Builder(solrBaseUrl).build();
        log.info("Solr client can be accessed at {}", solrBaseUrl);
    }

    @AfterAll
    public void tearDown() throws Exception {
        if (solrClient != null) {
            solrClient.close();
        }
        solrContainer.stop();
    }

    @BeforeEach
    public void beforeEach() throws Exception {
        log.info("Setting up test.\n\nSolr client can be accessed at {}\n\n\n", solrBaseUrl);
        // Create collection before each test
        CollectionAdminRequest.Create createCollection = CollectionAdminRequest.createCollection("documents", 1, 1);
        createCollection.process(solrClient);
        log.info("Creating temporary collection: {}", DEFAULT_COLLECTION);
        // Define schema for the collection
        addField(solrClient,"title", "text_general", false, DEFAULT_COLLECTION);
        addField(solrClient,"body", "text_general", false, DEFAULT_COLLECTION);
        addField(solrClient,"body_paragraphs", "text_general", true, DEFAULT_COLLECTION);
        addField(solrClient,"chunk-number", "pint", false, DEFAULT_COLLECTION);
        addField(solrClient, "chunk", "text_general", false, DEFAULT_COLLECTION);
        addField(solrClient, "parent-id", "string", false, DEFAULT_COLLECTION);
        addField(solrClient, "_nest_parent_", "string", false, DEFAULT_COLLECTION);
        addField(solrClient, "type", "string", false, DEFAULT_COLLECTION);
        addDenseVectorField(solrClient, "chunk-vector", 384, DEFAULT_COLLECTION);
        addDenseVectorField(solrClient, "title-vector", 384, DEFAULT_COLLECTION);
        addDenseVectorField(solrClient, "body-vector", 384, DEFAULT_COLLECTION);
        log.info("Creating temporary vector collection: {}", VECTOR_COLLECTION);
        CollectionAdminRequest.Create createVectorCollection = CollectionAdminRequest.createCollection(VECTOR_COLLECTION, 1, 1);
        createVectorCollection.process(solrClient);
        addField(solrClient, "parent-id", "string", false, VECTOR_COLLECTION);
        addField(solrClient,"chunk-number", "pint", false, VECTOR_COLLECTION);
        addField(solrClient, "chunk", "text_general", false, VECTOR_COLLECTION);
        addDenseVectorField(solrClient, "chunk-vector", 384, VECTOR_COLLECTION);

    }

    @AfterEach
    public void afterEach() throws Exception {
        log.info("on after each");
        // Delete collection after each test
        log.info("Deleting temporary collection: {}", DEFAULT_COLLECTION);
        CollectionAdminRequest.Delete deleteCollection = CollectionAdminRequest.deleteCollection(DEFAULT_COLLECTION);
        deleteCollection.process(solrClient);
        log.info("Deleting temporary vector collection: {}", VECTOR_COLLECTION);
        CollectionAdminRequest.Delete deleteVectorCollection = CollectionAdminRequest.deleteCollection(VECTOR_COLLECTION);
        deleteVectorCollection.process(solrClient);
    }

}