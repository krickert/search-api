 package com.krickert.search.api.test;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.util.NamedList;

import java.io.IOException;

public class InlineSearchTest extends BaseSolrTest {

    @Override
    protected void setupCollections() {
        try {
            String collectionName = "inline-test-collection";
            CollectionAdminRequest.Create createCollection = CollectionAdminRequest.createCollection(collectionName, 1, 1);
            NamedList<Object> response = solrClient.request(createCollection);
            // Additional seeding logic can go here
        } catch (SolrServerException | IOException e) {
            throw new RuntimeException("Failed to set up collections: " + e.getMessage(), e);
        }
    }

    @Override
    protected void deleteCollections() {
        try {
            String collectionName = "inline-test-collection";
            CollectionAdminRequest.Delete deleteCollection = CollectionAdminRequest.deleteCollection(collectionName);
            solrClient.request(deleteCollection);
        } catch (SolrServerException | IOException e) {
            throw new RuntimeException("Failed to delete collections: " + e.getMessage(), e);
        }
    }
}
