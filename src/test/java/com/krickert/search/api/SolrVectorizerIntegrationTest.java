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
        UpdateResponse deleteResponse = solrClient.deleteById(DEFAULT_COLLECTION, "1");
        solrClient.commit("documents");

        // Ensure the document was deleted successfully
        assertNotNull(deleteResponse);
        assertEquals(0, deleteResponse.getStatus());

        // Query again to ensure the document is deleted
        QueryResponse queryResponseAfterDelete = solrClient.query(DEFAULT_COLLECTION, new SolrQuery("id:1"));
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

    @Test
    public void testInsertVectorDocuments() throws Exception {
        // Step 1: Create the sample documents
        String title1 = "Test Title 1";
        String body1 = "This is a large body text for document 1. ".repeat(50); // 50 repetitions make it about 2000 chars
        String title2 = "Test Title 2";
        String body2 = "This is a large body text for document 2. ".repeat(50); // 50 repetitions make it about 2000 chars

        // Ensure the text bodies are approximately 2000 characters in length
        assertEquals(2100, body1.length());
        assertEquals(2100, body2.length());

        // Step 2: Generate embeddings using the gRPC vectorizer service
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub gRPCClient = context.getBean(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        EmbeddingsVectorReply title1Vector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(title1).build());
        EmbeddingsVectorReply body1Vector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(body1).build());
        EmbeddingsVectorReply title2Vector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(title2).build());
        EmbeddingsVectorReply body2Vector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(body2).build());

        // Confirm that the embeddings are generated correctly
        assertNotNull(title1Vector);
        assertEquals(384, title1Vector.getEmbeddingsList().size());
        assertNotNull(body1Vector);
        assertEquals(384, body1Vector.getEmbeddingsList().size());
        assertNotNull(title2Vector);
        assertEquals(384, title2Vector.getEmbeddingsList().size());
        assertNotNull(body2Vector);
        assertEquals(384, body2Vector.getEmbeddingsList().size());

        // Step 3: Prepare the Solr documents with embeddings
        SolrInputDocument document1 = new SolrInputDocument();
        document1.addField("id", "1");
        document1.addField("title", title1);
        document1.addField("body", body1);
        document1.addField("title-vector", title1Vector.getEmbeddingsList());
        document1.addField("body-vector", body1Vector.getEmbeddingsList());

        SolrInputDocument document2 = new SolrInputDocument();
        document2.addField("id", "2");
        document2.addField("title", title2);
        document2.addField("body", body2);
        document2.addField("title-vector", title2Vector.getEmbeddingsList());
        document2.addField("body-vector", body2Vector.getEmbeddingsList());

        // Step 4: Add and commit documents to Solr
        UpdateResponse updateResponse1 = solrClient.add(DEFAULT_COLLECTION, document1);
        UpdateResponse updateResponse2 = solrClient.add(DEFAULT_COLLECTION, document2);
        solrClient.commit(DEFAULT_COLLECTION);

        // Ensure the documents were added successfully
        assertNotNull(updateResponse1);
        assertEquals(0, updateResponse1.getStatus());
        assertNotNull(updateResponse2);
        assertEquals(0, updateResponse2.getStatus());

        // Step 5: Query and verify the documents
        QueryResponse queryResponse1 = solrClient.query(DEFAULT_COLLECTION, new SolrQuery("id:1"));
        SolrDocumentList documents1 = queryResponse1.getResults();
        assertEquals(1, documents1.getNumFound());
        assertEquals(title1, documents1.get(0).getFieldValue("title"));
        assertEquals(body1, documents1.get(0).getFieldValue("body"));

        QueryResponse queryResponse2 = solrClient.query(DEFAULT_COLLECTION, new SolrQuery("id:2"));
        SolrDocumentList documents2 = queryResponse2.getResults();
        assertEquals(1, documents2.getNumFound());
        assertEquals(title2, documents2.get(0).getFieldValue("title"));
        assertEquals(body2, documents2.get(0).getFieldValue("body"));
    }

    @Test
    public void testDenseVectorSearch() throws Exception {
        // Step 1: Create the sample documents
        String title1 = "Test Title 1";
        String body1 = "This is a large body text for document 1. ".repeat(50); // 50 repetitions make it about 2000 chars
        String title2 = "Test Title 2";
        String body2 = "This is a large body text for document 2. ".repeat(50); // 50 repetitions make it about 2000 chars

        // Ensure the text bodies are approximately 2000 characters in length
        assertEquals(2100, body1.length());
        assertEquals(2100, body2.length());

        // Step 2: Generate embeddings using the gRPC vectorizer service for documents
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub gRPCClient = context.getBean(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        EmbeddingsVectorReply title1Vector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(title1).build());
        EmbeddingsVectorReply body1Vector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(body1).build());
        EmbeddingsVectorReply title2Vector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(title2).build());
        EmbeddingsVectorReply body2Vector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(body2).build());

        // Confirm that the embeddings are generated correctly
        assertNotNull(title1Vector);
        assertEquals(384, title1Vector.getEmbeddingsList().size());
        assertNotNull(body1Vector);
        assertEquals(384, body1Vector.getEmbeddingsList().size());
        assertNotNull(title2Vector);
        assertEquals(384, title2Vector.getEmbeddingsList().size());
        assertNotNull(body2Vector);
        assertEquals(384, body2Vector.getEmbeddingsList().size());

        // Step 3: Prepare the Solr documents with embeddings
        SolrInputDocument document1 = new SolrInputDocument();
        document1.addField("id", "1");
        document1.addField("title", title1);
        document1.addField("body", body1);
        document1.addField("title-vector", title1Vector.getEmbeddingsList());
        document1.addField("body-vector", body1Vector.getEmbeddingsList());

        SolrInputDocument document2 = new SolrInputDocument();
        document2.addField("id", "2");
        document2.addField("title", title2);
        document2.addField("body", body2);
        document2.addField("title-vector", title2Vector.getEmbeddingsList());
        document2.addField("body-vector", body2Vector.getEmbeddingsList());

        // Add and commit documents to Solr
        UpdateResponse updateResponse1 = solrClient.add(DEFAULT_COLLECTION, document1);
        UpdateResponse updateResponse2 = solrClient.add(DEFAULT_COLLECTION, document2);
        solrClient.commit(DEFAULT_COLLECTION);

        // Ensure the documents were added successfully
        assertNotNull(updateResponse1);
        assertEquals(0, updateResponse1.getStatus());
        assertNotNull(updateResponse2);
        assertEquals(0, updateResponse2.getStatus());

        // Step 4: Perform dense vector search
        String queryText = "Test Title 1"; // Sample query text
        EmbeddingsVectorReply queryVectorReply = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(queryText).build());
        assertNotNull(queryVectorReply);
        assertEquals(384, queryVectorReply.getEmbeddingsList().size());

        // Create the dense vector query
        StringBuilder vectorQueryBuilder = new StringBuilder();
        vectorQueryBuilder.append("{!knn f=title-vector topK=10}[");
        for (int i = 0; i < queryVectorReply.getEmbeddingsList().size(); i++) {
            vectorQueryBuilder.append(queryVectorReply.getEmbeddingsList().get(i));
            if (i < queryVectorReply.getEmbeddingsList().size() - 1) {
                vectorQueryBuilder.append(",");
            }
        }
        vectorQueryBuilder.append("]");

        // Execute the dense vector search
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery(vectorQueryBuilder.toString());
        QueryResponse queryResponse = solrClient.query(DEFAULT_COLLECTION, solrQuery);

        // Validate the query response
        SolrDocumentList documents = queryResponse.getResults();
        assertTrue(documents.getNumFound() > 0); // Ensure that documents are found
        assertEquals("Test Title 1", documents.get(0).getFieldValue("title")); // Assuming the closest match is returned first
    }



}