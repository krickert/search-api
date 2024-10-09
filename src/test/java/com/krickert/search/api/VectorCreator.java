package com.krickert.search.api;

import com.krickert.search.service.EmbeddingServiceGrpc;
import com.krickert.search.service.EmbeddingsVectorReply;
import com.krickert.search.service.EmbeddingsVectorRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

public class VectorCreator {

    public static void main(String[] args) {
        // Configure gRPC channel
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 50401)
                .usePlaintext() // Don't use SSL for simplicity in this example
                .build();

        // Create the blocking stub
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingClient = EmbeddingServiceGrpc.newBlockingStub(channel);

        // Read text input from the user
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("Enter text to fetch embeddings for (or type 'quit' to exit): ");
            String text = scanner.nextLine();

            if ("quit".equalsIgnoreCase(text)) {
                System.out.println("Exiting...");
                break;
            }

            // Get embeddings
            EmbeddingsVectorRequest request = EmbeddingsVectorRequest.newBuilder()
                    .setText(text)
                    .build();

            try {
                EmbeddingsVectorReply reply = embeddingClient.createEmbeddingsVector(request);

                // Format embeddings
                List<Float> embeddings = reply.getEmbeddingsList();
                DecimalFormat decimalFormat = new DecimalFormat("0.0000000000");

                System.out.println("Embeddings:");
                String formattedEmbeddings = embeddings.stream()
                        .map(decimalFormat::format)
                        .collect(Collectors.joining(",", "[", "]"));
                System.out.println(formattedEmbeddings);
            } catch (Exception e) {
                System.err.println("Error fetching embeddings: " + e.getMessage());
            }
        }

        // Shut down the channel
        channel.shutdown();
    }
}