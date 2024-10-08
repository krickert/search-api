package com.krickert.search.api.grpc.client;

import com.krickert.search.api.config.VectorFieldInfo;
import com.krickert.search.service.EmbeddingServiceGrpc;
import com.krickert.search.service.EmbeddingsVectorReply;
import com.krickert.search.service.EmbeddingsVectorRequest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class VectorService {

    private final EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingClient;

    @Inject
    public VectorService(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    /**
     * Fetches the vector embeddings for a given text from the gRPC embedding service.
     *
     * @param text The input text to fetch the embeddings for.
     * @return The list of float embeddings.
     */
    public List<Float> getEmbeddingForText(String text) {
        try {
            EmbeddingsVectorRequest request = EmbeddingsVectorRequest.newBuilder()
                    .setText(text)
                    .build();

            EmbeddingsVectorReply reply = embeddingClient.createEmbeddingsVector(request);
            return reply.getEmbeddingsList();
        } catch (Exception e) {
            throw new RuntimeException("Error fetching embeddings for text: " + text, e);
        }
    }

    /**
     * Builds the vector query for a given vector field and embedding.
     *
     * @param vectorFieldInfo The vector field configuration.
     * @param embedding       The vector embeddings.
     * @param topK            The number of top results to fetch.
     * @return The Solr vector query.
     */
    public String buildVectorQueryForEmbedding(VectorFieldInfo vectorFieldInfo, List<Float> embedding, int topK, float boost) {
        if (vectorFieldInfo == null) {
            throw new IllegalArgumentException("VectorFieldInfo cannot be null");
        }

        return switch (vectorFieldInfo.getVectorFieldType()) {
            case INLINE -> buildVectorQuery(vectorFieldInfo.getVectorFieldName(), embedding, topK, boost);
            case EMBEDDED_DOC -> buildEmbeddedDocJoinQueryWithEmbedding(vectorFieldInfo.getVectorFieldName(), embedding, topK);
            case CHILD_COLLECTION ->
                    buildExternalCollectionJoinQueryWithEmbedding(vectorFieldInfo.getVectorFieldName(), vectorFieldInfo.getChunkCollection(), embedding, topK);
        };
    }

    /**
     * Builds a Solr vector query for an embedded document using a join.
     *
     * @param vectorField The vector field to be queried.
     * @param embedding   The vector embeddings.
     * @param topK        The number of top results to fetch.
     * @return The vector query for the embedded document using a join to the parent.
     */
    private String buildEmbeddedDocJoinQueryWithEmbedding(String vectorField, List<Float> embedding, int topK) {
        String knnQuery = buildVectorQuery(vectorField, embedding, topK, 0.0f);
        // Parent-child join query for embedded document
        return String.format("{!parent which=type:parent}%s", knnQuery);
    }

    /**
     * Builds a Solr vector query for a child collection using a join from an external collection.
     *
     * @param vectorField     The vector field to be queried.
     * @param chunkCollection The child collection where chunks are stored.
     * @param embedding       The vector embeddings.
     * @param topK            The number of top results to fetch.
     * @return The vector query for the child collection using a join.
     */
    private String buildExternalCollectionJoinQueryWithEmbedding(String vectorField, String chunkCollection, List<Float> embedding, int topK) {
        String knnQuery = buildVectorQuery(vectorField, embedding, topK, 0.0f);
        // External collection join query
        return String.format("{!join from=parent-id to=id fromIndex=%s}%s", chunkCollection, knnQuery);
    }

    /**
     * Utility function to build a KNN vector query in Solr format.
     *
     * @param field      The Solr field to search against.
     * @param embeddings The vector embeddings.
     * @param topK       The number of top results to fetch.
     * @return The vector query string.
     */
    private String buildVectorQuery(String field, List<Float> embeddings, int topK, float boost) {

        if (boost == 0.0f) {
            return String.format("({!knn f=%s topK=%d v=$vector})", field, topK);
        } else {
            return String.format("(({!knn f=%s topK=%d v=$vector})^%.5f)", field, topK, boost);
        }
    }
}