package com.krickert.search.api.grpc.client;

import com.krickert.search.service.EmbeddingServiceGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import jakarta.inject.Named;

@Factory
public class Clients {
    @Bean
    @Named("searchEmbeddingService")
    EmbeddingServiceGrpc.EmbeddingServiceBlockingStub inlineEmbeddingServiceBlockingStub (
            @GrpcChannel("${search-api.vector-grpc-channel}")
            ManagedChannel channel) {
        return EmbeddingServiceGrpc.newBlockingStub(
                channel
        );
    }
}
