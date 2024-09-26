package com.krickert.search.api.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SolrTest {

    public static final String DEFAULT_COLLECTION = "documents";
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

    protected static SolrClient solrClient;

    @BeforeAll
    public void setUp() throws Exception {
        solrContainer.start();
        log.info("Solr running on {}:{}", solrContainer.getHost(), solrContainer.getMappedPort(8983));

        String solrBaseUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr";
        solrClient = new Http2SolrClient.Builder(solrBaseUrl).build();
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
        // Create collection before each test
        CollectionAdminRequest.Create createCollection = CollectionAdminRequest.createCollection("documents", 1, 1);
        createCollection.process(solrClient);
        log.info("Creating temporary collection: {}", DEFAULT_COLLECTION);
        // Define schema for the collection
        addField("title", "string", false);
        addField("body", "text_general", false);
        SolrHelper.addDenseVectorField(solrClient, "documents", "title-vector", 384);
        SolrHelper.addDenseVectorField(solrClient, "documents", "body-vector", 384);

    }

    @AfterEach
    public void afterEach() throws Exception {
        // Delete collection after each test
        CollectionAdminRequest.Delete deleteCollection = CollectionAdminRequest.deleteCollection("documents");
        deleteCollection.process(solrClient);
    }

    protected void addField(String name, String type, boolean multiValued) throws Exception {
        Map<String, Object> fieldAttributes = new HashMap<>();
        fieldAttributes.put("name", name);
        fieldAttributes.put("type", type);
        fieldAttributes.put("multiValued", multiValued);

        SchemaRequest.AddField addFieldUpdate = new SchemaRequest.AddField(fieldAttributes);
        SchemaResponse.UpdateResponse addFieldResponse = addFieldUpdate.process(solrClient, "documents");

        assertNotNull(addFieldResponse);
        assertEquals(0, addFieldResponse.getStatus());
    }
}