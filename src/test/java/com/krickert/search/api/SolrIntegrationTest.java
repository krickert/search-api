package com.krickert.search.api;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.*;
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
public class SolrIntegrationTest {

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

    private static SolrClient solrClient;

    @BeforeAll
    public static void setUp() throws Exception {
        solrContainer.start();

        String solrBaseUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr";
        solrClient = new HttpSolrClient.Builder(solrBaseUrl).build();
    }

    @AfterAll
    public static void tearDown() throws Exception {
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

        // Define schema for the collection
        addField("title", "string", false);
    }

    @AfterEach
    public void afterEach() throws Exception {
        // Delete collection after each test
        CollectionAdminRequest.Delete deleteCollection = CollectionAdminRequest.deleteCollection("documents");
        deleteCollection.process(solrClient);
    }

    @Test
    public void testSolrIntegration() throws Exception {
        // Create a sample document
        SolrInputDocument document = new SolrInputDocument();
        document.addField("id", "1");
        document.addField("title", "Test Document");

        // Add the document to the collection
        UpdateResponse updateResponse = solrClient.add("documents", document);
        solrClient.commit("documents");

        // Ensure the document was added successfully
        assertNotNull(updateResponse);
        assertEquals(0, updateResponse.getStatus());

        // Query the document
        QueryResponse queryResponse = solrClient.query("documents", new SolrQuery("id:1"));
        SolrDocumentList documents = queryResponse.getResults();

        // Validate the document is added
        assertEquals(1, documents.getNumFound());
        assertEquals("Test Document", documents.get(0).getFieldValue("title"));

        // Delete the document
        UpdateResponse deleteResponse = solrClient.deleteById("documents", "1");
        solrClient.commit("documents");

        // Ensure the document was deleted successfully
        assertNotNull(deleteResponse);
        assertEquals(0, deleteResponse.getStatus());

        // Query again to ensure the document is deleted
        QueryResponse queryResponseAfterDelete = solrClient.query("documents", new SolrQuery("id:1"));
        SolrDocumentList documentsAfterDelete = queryResponseAfterDelete.getResults();

        // Validate the document is deleted
        assertTrue(documentsAfterDelete.isEmpty());
    }

    private void addField(String name, String type, boolean multiValued) throws Exception {
        Map<String, Object> fieldAttributes = new HashMap<>();
        fieldAttributes.put("name", name);
        fieldAttributes.put("type", type);
        fieldAttributes.put("multiValued", multiValued);

        SchemaRequest.AddField addFieldUpdate = new SchemaRequest.AddField(fieldAttributes);
        org.apache.solr.client.solrj.response.schema.SchemaResponse.UpdateResponse addFieldResponse = addFieldUpdate.process(solrClient, "documents");

        assertNotNull(addFieldResponse);
        assertEquals(0, addFieldResponse.getStatus());
    }
}