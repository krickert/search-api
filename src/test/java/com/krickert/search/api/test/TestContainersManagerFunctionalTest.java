package com.krickert.search.api.test;

import com.krickert.search.service.*;
import io.grpc.StatusRuntimeException;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class TestContainersManagerFunctionalTest extends BaseSolrTest {

    @Override
    protected void setupCollections() {
        try {
            // Create a collection specific for this test
            String collectionName = getClass().getSimpleName() + "-collection";
            CollectionAdminRequest.Create createCollection = CollectionAdminRequest.createCollection(collectionName, 1, 1);
            solrClient.request(createCollection);
        } catch (SolrServerException | IOException e) {
            fail("Failed to create Solr collection: " + e.getMessage());
        }
    }

    @Override
    protected void deleteCollections() {
        try {
            // Delete the collection after the test
            String collectionName = getClass().getSimpleName() + "-collection";
            CollectionAdminRequest.Delete deleteCollection = CollectionAdminRequest.deleteCollection(collectionName);
            solrClient.request(deleteCollection);
        } catch (SolrServerException | IOException e) {
            fail("Failed to delete Solr collection: " + e.getMessage());
        }
    }

    @Test
    public void testSolrContainerIsRunning() {
        try {
            // Check if Solr is accessible by pinging it
            NamedList<Object> response = solrClient.ping(getClass().getSimpleName() + "-collection").getResponse();
            assertNotNull(response);
            assertTrue(response.size() > 0);
        } catch (SolrServerException | IOException e) {
            fail("Solr is not accessible: " + e.getMessage());
        }
    }

    @Test
    public void testVectorizerServiceIsRunning() {
        try {
            // Make a sample request to the embedding service to check if it is running
            EmbeddingsVectorRequest request = EmbeddingsVectorRequest.newBuilder()
                    .setText("Sample text")
                    .build();
            EmbeddingsVectorReply response = embeddingServiceStub.createEmbeddingsVector(request);
            assertNotNull(response);
            assertEquals(384, response.getEmbeddingsList().size());
        } catch (StatusRuntimeException e) {
            fail("Vectorizer service is not accessible: " + e.getMessage());
        }
    }

    @Test
    public void testChunkerServiceIsRunning() {
        try {
            // Make a sample request to the chunker service to check if it is running
            ChunkRequest request = ChunkRequest.newBuilder()
                    .setText("Sample text for chunking")
                    .setOptions(ChunkOptions.newBuilder().setLength(600).setOverlap(100).build())
                    .build();
            ChunkReply response = chunkServiceStub.chunk(request);
            assertNotNull(response);
            assertTrue(response.getChunksCount() > 0);
        } catch (StatusRuntimeException e) {
            fail("Chunker service is not accessible: " + e.getMessage());
        }
    }

    @Test
    public void testSolrCollectionCreation() {
        try {
            // Test if a Solr collection can be created
            String collectionName = "additional-test-collection";
            CollectionAdminRequest.Create createCollection = CollectionAdminRequest.createCollection(collectionName, 1, 1);
            NamedList<Object> response = solrClient.request(createCollection);
            assertNotNull(response);
            assertTrue(response.size() > 0);

            // Clean up: Delete the collection after test
            CollectionAdminRequest.Delete deleteCollection = CollectionAdminRequest.deleteCollection(collectionName);
            solrClient.request(deleteCollection);
        } catch (SolrServerException | IOException e) {
            fail("Failed to create or delete Solr collection: " + e.getMessage());
        }
    }
}