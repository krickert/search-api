package com.krickert.search.api.test.basic;

import com.krickert.search.api.test.base.BaseSearchApiTest;
import com.krickert.search.model.pipe.PipeDocument;
import com.krickert.search.model.test.util.TestDataHelper;
import com.krickert.search.service.EmbeddingServiceGrpc;
import com.krickert.search.service.EmbeddingsVectorReply;
import com.krickert.search.service.EmbeddingsVectorRequest;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.krickert.search.api.solr.SolrHelper.buildVectorQuery;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class VectorizerIntegrationTest extends BaseSearchApiTest {
    @Override
    protected String getCollectionName() {
        return "vector-integration-test-collection";
    }

    @Override
    protected String getChunkCollectionName() {
        return "vector-integration-test-chunk-collection";
    }

    @Test
    public void testInsertVectorDocuments() throws Exception {
        String title1 = "Test Title 1";
        String body1 = "This is a large body text for document 1. ".repeat(50);
        String title2 = "Test Title 2";
        String body2 = "This is a large body text for document 2. ".repeat(50);

        List<Float> title1Vector = vectorService.getEmbeddingForText(title1);
        List<Float> body1Vector = vectorService.getEmbeddingForText(body1);
        List<Float> title2Vector = vectorService.getEmbeddingForText(title2);
        List<Float> body2Vector = vectorService.getEmbeddingForText(body2);

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

        UpdateResponse updateResponse1 = solrClient.add(getCollectionName(), document1);
        UpdateResponse updateResponse2 = solrClient.add(getCollectionName(), document2);
        solrClient.commit(getCollectionName());

        assertNotNull(updateResponse1);
        assertEquals(0, updateResponse1.getStatus());
        assertNotNull(updateResponse2);
        assertEquals(0, updateResponse2.getStatus());

        QueryResponse queryResponse1 = solrClient.query(getCollectionName(), new SolrQuery("id:1"));
        SolrDocumentList documents1 = queryResponse1.getResults();
        assertEquals(1, documents1.getNumFound());
        assertEquals(title1, documents1.get(0).getFieldValue("title"));
        assertEquals(body1, documents1.get(0).getFieldValue("body"));

        QueryResponse queryResponse2 = solrClient.query(getCollectionName(), new SolrQuery("id:2"));
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
        UpdateResponse updateResponse = solrClient.add(getCollectionName(), document);
        solrClient.commit(getCollectionName());

        // Ensure the document was added successfully
        assertNotNull(updateResponse);
        assertEquals(0, updateResponse.getStatus());

        // Query the document
        QueryResponse queryResponse = solrClient.query(getCollectionName(), new SolrQuery("id:1"));
        SolrDocumentList documents = queryResponse.getResults();

        // Validate the document is added
        assertEquals(1, documents.getNumFound());
        assertEquals("Test Document", documents.get(0).getFieldValue("title"));

        // Delete the document
        UpdateResponse deleteResponse = solrClient.deleteById(getCollectionName(), "1");
        solrClient.commit(getCollectionName());

        // Ensure the document was deleted successfully
        assertNotNull(deleteResponse);
        assertEquals(0, deleteResponse.getStatus());

        // Query again to ensure the document is deleted
        QueryResponse queryResponseAfterDelete = solrClient.query(getCollectionName(), new SolrQuery("id:1"));
        SolrDocumentList documentsAfterDelete = queryResponseAfterDelete.getResults();

        // Validate the document is deleted
        assertTrue(documentsAfterDelete.isEmpty());
    }

    @Test
    public void testVectorizerGRPCConnection() {
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
            inputDocument.addField("title-vector", vectorService.getEmbeddingForText(doc.getTitle()));
            inputDocument.addField("body-vector", vectorService.getEmbeddingForText(doc.getBody()));
            solrDocs.add(inputDocument);
        }

        try {
            UpdateResponse updateResponse = solrClient.add(getCollectionName(), solrDocs);
            solrClient.commit(getCollectionName());
            assertNotNull(updateResponse);
            assertEquals(0, updateResponse.getStatus());
        } catch (SolrServerException | IOException e) {
            fail(e);
        }

        String queryText = "maintaining computers in large organizations";
        List<Float> titleQueryVector = vectorService.getEmbeddingForText(queryText);

        String titleVectorQuery = buildVectorQuery("title-vector", titleQueryVector, 30);

        SolrQuery solrTitleQuery = new SolrQuery();
        solrTitleQuery.setQuery(titleVectorQuery);
        solrTitleQuery.setRows(30);

        QueryResponse titleQueryResponse = null;
        try {
            titleQueryResponse = solrClient.query(getCollectionName(), solrTitleQuery);
        } catch (SolrServerException | IOException e) {
            fail(e);
        }

        assertNotNull(titleQueryResponse);
        SolrDocumentList titleDocuments = titleQueryResponse.getResults();
        assertEquals(30, titleDocuments.size());
    }
}
