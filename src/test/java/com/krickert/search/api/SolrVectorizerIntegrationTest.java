package com.krickert.search.api;

import com.krickert.search.api.solr.ProtobufToSolrDocument;
import com.krickert.search.api.solr.SolrHelper;
import com.krickert.search.api.solr.SolrTest;
import com.krickert.search.model.pipe.PipeDocument;
import com.krickert.search.model.test.util.TestDataHelper;
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
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import static com.krickert.search.api.solr.SolrHelper.buildVectorQuery;
import static org.junit.jupiter.api.Assertions.*;

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
    ProtobufToSolrDocument protobufToSolrDocument;

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

        // Create the dense vector query using the utility function
        String vectorQuery = buildVectorQuery("title-vector", queryVectorReply.getEmbeddingsList(), 10);

        // Execute the dense vector search
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery(vectorQuery);
        QueryResponse queryResponse = solrClient.query(DEFAULT_COLLECTION, solrQuery);

        // Validate the query response
        SolrDocumentList documents = queryResponse.getResults();
        assertTrue(documents.getNumFound() > 0); // Ensure that documents are found
        assertEquals("Test Title 1", documents.get(0).getFieldValue("title")); // Assuming the closest match is returned first
    }

    @Test
    public void sampleWikiDocumentDenseVectorSearchTest() {
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub gRPCClient = context.getBean(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        Collection<PipeDocument> docs = TestDataHelper.getFewHunderedPipeDocuments();
        Collection<SolrInputDocument> solrDocs = new ArrayList<>();

        for (PipeDocument doc : docs) {
            SolrInputDocument inputDocument = protobufToSolrDocument.convertProtobufToSolrDocument(doc);

            inputDocument.addField("title-vector", gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(doc.getTitle()).build()).getEmbeddingsList());
            inputDocument.addField("body-vector", gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(doc.getBody()).build()).getEmbeddingsList());

            solrDocs.add(inputDocument);
        }

        try {
            UpdateResponse updateResponse1 = solrClient.add(DEFAULT_COLLECTION, solrDocs);
            solrClient.commit(DEFAULT_COLLECTION);

        } catch (SolrServerException | IOException e) {
            fail(e);
        }

        // The target query text for KNN search
        String queryText = "maintaining computers in large organizations";

        // Generate embeddings for the query text
        EmbeddingsVectorReply titleQueryVector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(queryText).build());
        EmbeddingsVectorReply bodyQueryVector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(queryText).build());

        // Confirm that the embeddings are generated correctly
        assertNotNull(titleQueryVector);
        assertEquals(384, titleQueryVector.getEmbeddingsList().size());
        assertNotNull(bodyQueryVector);
        assertEquals(384, bodyQueryVector.getEmbeddingsList().size());

        // Create vector queries using utility function
        String titleVectorQuery = SolrHelper.buildVectorQuery("title-vector", titleQueryVector.getEmbeddingsList(), 30);
        String bodyVectorQuery = SolrHelper.buildVectorQuery("body-vector", bodyQueryVector.getEmbeddingsList(), 30);

        // Execute KNN search for title vector
        SolrQuery solrTitleQuery = new SolrQuery();
        solrTitleQuery.setQuery(titleVectorQuery);
        solrTitleQuery.setRows(30); // Ensure Solr returns 30 results
        QueryResponse titleQueryResponse = null;
        try {
            titleQueryResponse = solrClient.query(DEFAULT_COLLECTION, solrTitleQuery);
        } catch (SolrServerException | IOException e) {
            fail(e);
        }

        // Execute KNN search for body vector
        SolrQuery solrBodyQuery = new SolrQuery();
        solrBodyQuery.setQuery(bodyVectorQuery);
        solrBodyQuery.setRows(30); // Ensure Solr returns 30 results
        QueryResponse bodyQueryResponse = null;
        try {
            bodyQueryResponse = solrClient.query(DEFAULT_COLLECTION, solrBodyQuery);
        } catch (SolrServerException | IOException e) {
            fail(e);
        }

        // Validate the query responses
        assertNotNull(titleQueryResponse);
        assertNotNull(bodyQueryResponse);

        SolrDocumentList titleDocuments = titleQueryResponse.getResults();
        SolrDocumentList bodyDocuments = bodyQueryResponse.getResults();

        assertEquals(30, titleDocuments.size()); // Ensure that 30 documents are returned
        assertEquals(30, bodyDocuments.size()); // Ensure that 30 documents are returned

        // Log results for debugging purposes (optional)
        log.info("Title KNN Search Results:");
        titleDocuments.forEach(doc -> log.info("Doc ID: {}, Title: {}", doc.getFieldValue("id"), doc.getFieldValue("title")));

        log.info("Body KNN Search Results:");
        bodyDocuments.forEach(doc -> log.info("Doc ID: {}, TItle: {}", doc.getFieldValue("id"), doc.getFieldValue("title")));

        // Additional assertions can be added based on expected results
    }

    @Test
    public void sampleWikiDocumentKeywordAndBoostedDenseVectorSearchTest() {
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub gRPCClient = context.getBean(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        Collection<PipeDocument> docs = TestDataHelper.getFewHunderedPipeDocuments();
        Collection<SolrInputDocument> solrDocs = new ArrayList<>();

        for (PipeDocument doc : docs) {
            SolrInputDocument inputDocument = protobufToSolrDocument.convertProtobufToSolrDocument(doc);

            inputDocument.addField("title-vector", gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(doc.getTitle()).build()).getEmbeddingsList());
            inputDocument.addField("body-vector", gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(doc.getBody()).build()).getEmbeddingsList());

            solrDocs.add(inputDocument);
        }

        try {
            solrClient.add(DEFAULT_COLLECTION, solrDocs);
            solrClient.commit(DEFAULT_COLLECTION);
        } catch (SolrServerException | IOException e) {
            fail(e);
        }

        // The target query text for KNN search
        String queryText = "maintaining computers in large organizations";

        // Generate embeddings for the query text
        EmbeddingsVectorReply titleQueryVector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(queryText).build());
        EmbeddingsVectorReply bodyQueryVector = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(queryText).build());

        // Confirm that the embeddings are generated correctly
        assertNotNull(titleQueryVector);
        assertEquals(384, titleQueryVector.getEmbeddingsList().size());
        assertNotNull(bodyQueryVector);
        assertEquals(384, bodyQueryVector.getEmbeddingsList().size());

        // Create vector queries using a utility function
        String titleVectorQuery = SolrHelper.buildVectorQuery("title-vector", titleQueryVector.getEmbeddingsList(), 30);
        String bodyVectorQuery = SolrHelper.buildVectorQuery("body-vector", bodyQueryVector.getEmbeddingsList(), 30);

        // Keyword search on both title and body
        String keywordQuery = "title:(" + queryText + ") OR body:(" + queryText + ")";

        // Boosted query
        SolrQuery solrBoostedQuery = new SolrQuery();
        solrBoostedQuery.setQuery(keywordQuery);
        solrBoostedQuery.set("defType", "edismax");
        solrBoostedQuery.set("boost", titleVectorQuery);
        solrBoostedQuery.setRows(30); // Ensure Solr returns 30 results

        log.info("This is a keyword query that boosts based on the semantic response. It uses the boost field in eDisMax for now, but may want to do a bq instead.\n{}", solrBoostedQuery);

        QueryResponse boostedQueryResponse;
        try {
            boostedQueryResponse = solrClient.query(DEFAULT_COLLECTION, solrBoostedQuery, SolrRequest.METHOD.POST);
        } catch (SolrServerException | IOException e) {
            fail(e);
            throw new RuntimeException(e);
        }

        // Validate the boosted query response
        assertNotNull(boostedQueryResponse);

        SolrDocumentList boostedDocuments = boostedQueryResponse.getResults();
        assertEquals(30, boostedDocuments.size()); // Ensure that 30 documents are returned

        // Log results for debugging purposes (optional)
        log.info("Boosted Search Results:");
        boostedDocuments.forEach(doc -> log.info("Doc ID: {}, Title: {}", doc.getFieldValue("id"), doc.getFieldValue("title")));

        // Perform the keyword query without boost
        SolrQuery solrKeywordQuery = new SolrQuery();
        solrKeywordQuery.setQuery(keywordQuery);
        solrKeywordQuery.set("defType", "edismax");
        solrKeywordQuery.setRows(30); // Ensure Solr returns 30 results

        QueryResponse keywordQueryResponse;
        try {
            keywordQueryResponse = solrClient.query(DEFAULT_COLLECTION, solrKeywordQuery, SolrRequest.METHOD.POST);
        } catch (SolrServerException | IOException e) {
            fail(e);
            throw new RuntimeException(e);
        }

        // Validate the keyword query response
        assertNotNull(keywordQueryResponse);

        SolrDocumentList keywordDocuments = keywordQueryResponse.getResults();
        assertEquals(30, keywordDocuments.size()); // Ensure that 30 documents are returned

        log.info("Keyword Search Results:");
        keywordDocuments.forEach(doc -> log.info("Doc ID: {}, Title: {}", doc.getFieldValue("id"), doc.getFieldValue("title")));

        // Assertion 1: The number of keyword results is equal to the number of boosted results.
        assertEquals(keywordQueryResponse.getResults().getNumFound(), boostedQueryResponse.getResults().getNumFound());

        // Assertion 2: The order of the documents are not the same between each search
        boolean isOrderSame = true;
        for (int i = 0; i < boostedDocuments.size(); i++) {
            if (!boostedDocuments.get(i).getFieldValue("id").equals(keywordDocuments.get(i).getFieldValue("id"))) {
                isOrderSame = false;
                break;
            }
        }
        assertFalse(isOrderSame, "The document order should not be the same between boosted and keyword searches.");

        // Assertion 3: The first three docs from the boosted results have IDs 41565, 41291, and 41578
        assertEquals("41565", boostedDocuments.get(0).getFieldValue("id"));
        assertEquals("41291", boostedDocuments.get(1).getFieldValue("id"));
        assertEquals("41578", boostedDocuments.get(2).getFieldValue("id"));
    }
}