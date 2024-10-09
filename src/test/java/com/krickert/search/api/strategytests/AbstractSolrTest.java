package com.krickert.search.api.strategytests;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractSolrTest {
    protected static final String DEFAULT_COLLECTION = "documents";
    protected List<String> collectionsCreated = new ArrayList<>();
    protected static final Logger log = LoggerFactory.getLogger(AbstractSolrTest.class);

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

    // Static initializer to set system property before Micronaut context initializes
    static {
        try {
            // Start the Solr container
            solrContainer.start();
            String host = solrContainer.getHost();
            int port = solrContainer.getMappedPort(8983);
            solrBaseUrl = "http://" + host + ":" + port + "/solr";
            solrClient = new Http2SolrClient.Builder(solrBaseUrl).build();
            log.info("Solr client can be accessed at {}", solrBaseUrl);

            // Set the system property for Micronaut to pick up
            System.setProperty("search-api.solr.url", solrBaseUrl);
            log.info("Set system property 'search-api.solr.url' to {}", solrBaseUrl);
        } catch (Exception e) {
            log.error("Error initializing Solr container: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @BeforeAll
    public void setUp() throws Exception {
        if (!solrContainer.isRunning()) {
            solrContainer.start();
        }
        if (solrClient == null) {
            solrClient = new Http2SolrClient.Builder(solrBaseUrl).build();
            log.info("Solr client can be accessed at {}", solrBaseUrl);
        }

        log.info("AbstractSolrTest setUp completed.");
    }

    @AfterAll
    public void tearDown() throws Exception {
        if (solrClient != null) {
            solrClient.close();
        }
        solrContainer.stop();
        log.info("AbstractSolrTest tearDown completed.");
    }

    @BeforeEach
    public void beforeEach() throws Exception {
        checkSolrConnection();
        log.info("Setting up Solr collections and schema.");
        setupSolrCollectionsAndSchema();
        log.info("Seeding Solr collections with data.");
        seedCollection();
    }

    private void checkSolrConnection() {
        try {
            solrClient.ping("dummy");
        } catch (SolrServerException | IOException e) {
            log.debug("exception thrown", e);
            solrClient = new Http2SolrClient.Builder(solrBaseUrl).build();
        }

    }

    @AfterEach
    public void afterEach() throws Exception {
        log.info("Deleting collections.");
        deleteCollections();
    }

    /**
     * Abstract method to set up Solr collections and define schema.
     * This must be implemented by subclasses.
     */
    protected abstract void setupSolrCollectionsAndSchema() throws Exception;

    /**
     * Abstract method to seed the collection with data.
     * This must be implemented by subclasses.
     */
    protected abstract void seedCollection() throws Exception;

    /**
     * Deletes the collections created during the test.
     */
    protected void deleteCollections() throws Exception {
        log.info("Deleting collections.");
        if (solrClient != null) {
            for (String collection : collectionsCreated) {
                log.info("Deleting collection: {}", collection);
                try {
                    solrClient.request(CollectionAdminRequest.deleteCollection(collection));
                } catch (SolrServerException e) {
                    if (e.getMessage().contains("Could not find collection")) {
                        log.warn("Collection {} does not exist and cannot be deleted.", collection);
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    /**
     * Helper method to create a collection.
     */
    protected void createCollection(String collectionName, int numShards, int numReplicas) throws Exception {
        CollectionAdminRequest.Create createRequest = CollectionAdminRequest.createCollection(collectionName, numShards, numReplicas);
        solrClient.request(createRequest);
        collectionsCreated.add(collectionName); // Track the created collection
        log.info("Created Solr collection: {}", collectionName);
    }

    protected Http2SolrClient getSolrClient() {
        return solrClient;
    }
}