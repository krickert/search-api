package com.krickert.search.api.test;

import com.krickert.search.api.test.base.BaseSolrTest;
import com.krickert.search.service.*;
import io.grpc.StatusRuntimeException;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class TestContainersManagerFunctionalTest extends BaseSolrTest {

    private final String uniqueCollectionName = getClass().getSimpleName() + "-collection-" + Instant.now().toEpochMilli();

    @Override
    protected void setupCollections() {
        try {
            if (!collectionExists(uniqueCollectionName)) {
                CollectionAdminRequest.Create createCollection = CollectionAdminRequest.createCollection(uniqueCollectionName, 1, 1);
                solrClient.request(createCollection);
            }
        } catch (SolrServerException | IOException e) {
            fail("Failed to create Solr collection: " + e.getMessage());
        }
    }

    @Override
    protected void deleteCollections() {
        try {
            if (collectionExists(uniqueCollectionName)) {
                CollectionAdminRequest.Delete deleteCollection = CollectionAdminRequest.deleteCollection(uniqueCollectionName);
                solrClient.request(deleteCollection);
            }
        } catch (SolrServerException | IOException e) {
            // Log the error but do not fail the test during cleanup
            System.err.println("Failed to delete Solr collection: " + e.getMessage());
        }
    }

    @Override
    protected String getCollectionName() {
        return uniqueCollectionName;
    }

    @Override
    protected String getChunkCollectionName() {
        return null;
    }

    private boolean collectionExists(String collectionName) {
        try {
            CollectionAdminRequest.List listRequest = new CollectionAdminRequest.List();
            NamedList<Object> response = solrClient.request(listRequest);
            return response.get("collections").toString().contains(collectionName);
        } catch (SolrServerException | IOException e) {
            fail("Failed to check if collection exists: " + e.getMessage());
            return false;
        }
    }

    @Test
    public void testSolrContainerIsRunning() {
        try {
            NamedList<Object> response = solrClient.ping(uniqueCollectionName).getResponse();
            assertNotNull(response);
            assertTrue(response.size() > 0);
        } catch (SolrServerException | IOException e) {
            fail("Solr is not accessible: " + e.getMessage());
        }
    }

    @Test
    public void testVectorizerServiceIsRunning() {
        try {
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
            String collectionName = "additional-test-collection-" + Instant.now().toEpochMilli();
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