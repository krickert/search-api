package com.krickert.search.api.test.old;

import com.krickert.search.api.solr.ProtobufToSolrDocument;
import com.krickert.search.model.pipe.PipeDocument;
import com.krickert.search.model.test.util.TestDataHelper;
import com.krickert.search.service.EmbeddingServiceGrpc;
import com.krickert.search.service.EmbeddingsVectorReply;
import com.krickert.search.service.EmbeddingsVectorRequest;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.krickert.search.api.solr.SolrHelper.buildVectorQuery;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MicronautTest(environments = "test")
public class SolrVectorizerIntegrationTest extends SolrTest {

    private static final Logger log = LoggerFactory.getLogger(SolrVectorizerIntegrationTest.class);

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
        super.setUp();
        vectorizerContainer.start();

        vectorizerHost = vectorizerContainer.getHost();
        vectorizerPort = vectorizerContainer.getMappedPort(50401);


        // Set the dynamic port property in the application context
        context.getEnvironment().addPropertySource(PropertySource.of(
                "test",
                Collections.singletonMap("solr-test.vectorizer-mapped-port", vectorizerPort)
        ));

        // Set the dynamic port property in the application context
        context.getEnvironment().addPropertySource(PropertySource.of(
                "test",
                Collections.singletonMap("search-api.vector-default.vector-grpc-channel", "http://localhost:" + vectorizerPort)
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
        super.tearDown();
    }

    private List<Float> getOrCacheVector(String text) {
        log.debug("Generating vector from vectorizer for text: {}", text);
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub gRPCClient = context.getBean(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        EmbeddingsVectorReply reply = gRPCClient.createEmbeddingsVector(EmbeddingsVectorRequest.newBuilder().setText(text).build());

        List<Float> vector = reply.getEmbeddingsList();
        assertNotNull(vector);
        assertEquals(384, vector.size(), "Vector size should be 384.");
        return vector;
    }

    @Test
    public void testInsertVectorDocuments() throws Exception {
        String title1 = "Test Title 1";
        String body1 = "This is a large body text for document 1. ".repeat(50);
        String title2 = "Test Title 2";
        String body2 = "This is a large body text for document 2. ".repeat(50);

        List<Float> title1Vector = getOrCacheVector(title1);
        List<Float> body1Vector = getOrCacheVector(body1);
        List<Float> title2Vector = getOrCacheVector(title2);
        List<Float> body2Vector = getOrCacheVector(body2);

        SolrInputDocument document1 = new SolrInputDocument();
        document1.addField("id", "1");
        document1.addField("title", title1);
        document1.addField("body", body1);
        document1.addField("title-vector", title1Vector);
        document1.addField("body-vector", body1Vector);

        SolrInputDocument document2 = new SolrInputDocument();
        document2.addField("id", "2");
        document2.addField("title", title2);
        document2.addField("body", body2);
        document2.addField("title-vector", title2Vector);
        document2.addField("body-vector", body2Vector);

        UpdateResponse updateResponse1 = solrClient.add(DEFAULT_COLLECTION, document1);
        UpdateResponse updateResponse2 = solrClient.add(DEFAULT_COLLECTION, document2);
        solrClient.commit(DEFAULT_COLLECTION);

        assertNotNull(updateResponse1);
        assertEquals(0, updateResponse1.getStatus());
        assertNotNull(updateResponse2);
        assertEquals(0, updateResponse2.getStatus());

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
    public void sampleWikiDocumentDenseVectorSearchTest() {
        Collection<PipeDocument> docs = TestDataHelper.getFewHunderedPipeDocuments();
        Collection<SolrInputDocument> solrDocs = new ArrayList<>();

        for (PipeDocument doc : docs) {
            SolrInputDocument inputDocument = protobufToSolrDocument.convertProtobufToSolrDocument(doc);
            inputDocument.addField("title-vector", getOrCacheVector(doc.getTitle()));
            inputDocument.addField("body-vector", getOrCacheVector(doc.getBody()));
            solrDocs.add(inputDocument);
        }

        try {
            UpdateResponse updateResponse = solrClient.add(DEFAULT_COLLECTION, solrDocs);
            solrClient.commit(DEFAULT_COLLECTION);
            assertNotNull(updateResponse);
            assertEquals(0, updateResponse.getStatus());
        } catch (SolrServerException | IOException e) {
            fail(e);
        }

        String queryText = "maintaining computers in large organizations";
        List<Float> titleQueryVector = getOrCacheVector(queryText);
        List<Float> bodyQueryVector = getOrCacheVector(queryText);

        String titleVectorQuery = buildVectorQuery("title-vector", titleQueryVector, 30);
        String bodyVectorQuery = buildVectorQuery("body-vector", bodyQueryVector, 30);

        SolrQuery solrTitleQuery = new SolrQuery();
        solrTitleQuery.setQuery(titleVectorQuery);
        solrTitleQuery.setRows(30);

        QueryResponse titleQueryResponse = null;
        try {
            titleQueryResponse = solrClient.query(DEFAULT_COLLECTION, solrTitleQuery);
        } catch (SolrServerException | IOException e) {
            fail(e);
        }

        assertNotNull(titleQueryResponse);
        SolrDocumentList titleDocuments = titleQueryResponse.getResults();
        assertEquals(30, titleDocuments.size());
    }
}
