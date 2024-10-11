package com.krickert.search.api.test;

import com.krickert.search.service.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;

public abstract class BaseSolrTest {

    protected Http2SolrClient solrClient;
    protected ManagedChannel vectorizerChannel;
    protected EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingServiceStub;
    protected ManagedChannel chunkerChannel;
    protected ChunkServiceGrpc.ChunkServiceBlockingStub chunkServiceStub;

    static {
        TestContainersManager.startContainers(); // Containers will be started only once for the entire suite
    }

    @BeforeEach
    public void setUp() {
        // Set up new client instances for each test
        solrClient = TestContainersManager.createSolrClient();

        vectorizerChannel = ManagedChannelBuilder.forTarget(TestContainersManager.getVectorizerUrl().replace("http://", ""))
                .usePlaintext()
                .build();
        embeddingServiceStub = EmbeddingServiceGrpc.newBlockingStub(vectorizerChannel);

        chunkerChannel = ManagedChannelBuilder.forTarget(TestContainersManager.getChunkerUrl().replace("http://", ""))
                .usePlaintext()
                .build();
        chunkServiceStub = ChunkServiceGrpc.newBlockingStub(chunkerChannel);

        // Set up collections for each test
        setupCollections();
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Cleanup collections after each test
        deleteCollections();

        // Close clients
        if (solrClient != null) {
            solrClient.close();
        }
        if (vectorizerChannel != null && !vectorizerChannel.isShutdown()) {
            vectorizerChannel.shutdownNow();
        }
        if (chunkerChannel != null && !chunkerChannel.isShutdown()) {
            chunkerChannel.shutdownNow();
        }
    }

    protected abstract void setupCollections();

    protected abstract void deleteCollections();
}