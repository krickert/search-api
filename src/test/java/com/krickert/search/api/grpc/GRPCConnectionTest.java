package com.krickert.search.api.grpc;

import com.krickert.search.service.EmbeddingServiceGrpc;
import com.krickert.search.service.EmbeddingsVectorReply;
import com.krickert.search.service.EmbeddingsVectorRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class GRPCConnectionTest {

    @Inject
    EmbeddingServiceGrpc.EmbeddingServiceBlockingStub gRPCClient;

    @Test
    public void testGRPCServiceConnection() {
        EmbeddingsVectorRequest request = EmbeddingsVectorRequest.newBuilder().setText("Testing 1 2 3").build();
        EmbeddingsVectorReply reply = gRPCClient.createEmbeddingsVector(request);
        assertNotNull(reply, "Response from gRPC service should not be null");
        assertFalse(reply.getEmbeddingsList().isEmpty(), "Embeddings should be generated");
    }
}