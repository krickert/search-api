package com.krickert.search.api.test.old.grpc;

import com.krickert.search.service.EmbeddingServiceGrpc;
import com.krickert.search.service.EmbeddingsVectorReply;
import com.krickert.search.service.EmbeddingsVectorRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@MicronautTest
public class GRPCConnectionTest {

    @Inject
    EmbeddingServiceGrpc.EmbeddingServiceBlockingStub gRPCClient;

    @BeforeEach
    void setup() {
        // Mock the gRPC client
        gRPCClient = Mockito.mock(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        EmbeddingsVectorReply mockReply = EmbeddingsVectorReply.newBuilder().addEmbeddings(0.1f).addEmbeddings(0.2f).build();
        when(gRPCClient.createEmbeddingsVector(any(EmbeddingsVectorRequest.class))).thenReturn(mockReply);
    }

    @Test
    public void testGRPCServiceConnection() {
        EmbeddingsVectorRequest request = EmbeddingsVectorRequest.newBuilder().setText("Testing 1 2 3").build();
        EmbeddingsVectorReply reply = gRPCClient.createEmbeddingsVector(request);
        assertNotNull(reply, "Response from gRPC service should not be null");
        assertFalse(reply.getEmbeddingsList().isEmpty(), "Embeddings should be generated");
    }
}