package com.krickert.search.api.test.basic;

import com.krickert.search.api.test.base.BaseSearchApiTest;
import io.micronaut.core.annotation.NonNull;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SolrIntegarionTest extends BaseSearchApiTest {

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

    @Override
    protected String getCollectionName() {
        return "basic-test-collection";
    }

    @Override
    protected String getChunkCollectionName() {
        return null;
    }

    @Override
    public Map<String, String> get() {
        return super.get();
    }

    @Override
    public @NonNull Map<String, String> getProperties() {
        return Map.of();
    }
}
