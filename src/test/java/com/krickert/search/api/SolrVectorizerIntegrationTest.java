package com.krickert.search.api;

import com.krickert.search.api.solr.SolrTest;
import com.krickert.search.service.EmbeddingServiceGrpc;
import com.krickert.search.service.EmbeddingsVectorReply;
import com.krickert.search.service.EmbeddingsVectorRequest;
import io.grpc.ManagedChannel;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MicronautTest(environments = "test") // Ensure the correct environment is used
public class SolrVectorizerIntegrationTest extends SolrTest {

    private static final Logger log = LoggerFactory.getLogger(SolrVectorizerIntegrationTest.class);

    @Singleton
    EmbeddingServiceGrpc.EmbeddingServiceBlockingStub reactiveStub(
            @GrpcChannel("https://localhost:${solr-test.vectorizer-mapped-port}")
            ManagedChannel channel) {
        return EmbeddingServiceGrpc.newBlockingStub(
                channel
        );
    }

    @SuppressWarnings("resource")
    @Container
    public static GenericContainer<?> vectorizerContainer = new GenericContainer<>(DockerImageName.parse("krickert/vectorizer:1.0-SNAPSHOT"))
            .withExposedPorts(50401);

    private static String vectorizerHost;
    private static Integer vectorizerPort;

    @Inject
    ApplicationContext context;

    @BeforeAll
    @Override
    @SuppressWarnings("resource")
    public void setUp() throws Exception {
        super.setUp(); // Call the parent method to setup Solr

        vectorizerContainer.start();

        vectorizerHost = vectorizerContainer.getHost();
        vectorizerPort = vectorizerContainer.getMappedPort(50401);


        // Set the dynamic port property in the application context
        context.getEnvironment().addPropertySource(PropertySource.of(
                "test",
                Collections.singletonMap("solr-test.vectorizer-mapped-port", vectorizerPort)
        ));

        // Logging for debugging
        log.info("Vectorizer running on {}:{}", vectorizerHost, vectorizerPort);
    }

    @AfterAll
    @Override
    public void tearDown() throws Exception {
        if (vectorizerContainer != null) {
            vectorizerContainer.stop();
        }
        super.tearDown(); // Call the parent method to teardown Solr
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

    @Test
    public void testVectorizerGRPCConnection() {
        // Logging the vectorizer details
        log.info("Using vectorizer service at {}:{}", vectorizerHost, vectorizerPort);
        assertNotNull(vectorizerHost);
        assertNotNull(vectorizerPort);

        // Now you can access the gRPC client bean (EmbeddingServiceGrpc.EmbeddingServiceBlockingStub)
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub gRPCClient = context.getBean(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);

        EmbeddingsVectorReply reply = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText("Testing 1 2 3").build());
        assertNotNull(reply);
        assertEquals(384, reply.getEmbeddingsList().size());
    }

}