package com.krickert.search.api.grpc.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.api.config.VectorFieldInfo;
import com.krickert.search.api.solr.EmbeddedInfinispanCache;
import com.krickert.search.service.EmbeddingServiceGrpc;
import com.krickert.search.service.EmbeddingsVectorReply;
import com.krickert.search.service.EmbeddingsVectorRequest;
import io.micronaut.cache.annotation.CacheConfig;
import io.micronaut.cache.annotation.Cacheable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.cache.Cache;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class VectorService {

    private static final Logger log = LoggerFactory.getLogger(VectorService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingClient;
    private final Cache<String, String> vectorCache;

    @Inject
    public VectorService(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingClient, EmbeddedInfinispanCache embeddedInfinispanCache) {
        this.embeddingClient = embeddingClient;
        this.vectorCache = embeddedInfinispanCache.getCache();
    }

    /**
     * Fetches the vector embeddings for a given text from the gRPC embedding service.
     *
     * @param text The input text to fetch the embeddings for.
     * @return The list of float embeddings.
     */
    public List<Float> getEmbeddingForText(String text) {
        try {
            String cachedEmbeddingBase64 = vectorCache.get(text);
            if (cachedEmbeddingBase64 != null) {
                log.info("Cache hit for text: {}", text);
                byte[] decodedBytes = Base64.getDecoder().decode(cachedEmbeddingBase64);
                return objectMapper.readValue(decodedBytes, objectMapper.getTypeFactory().constructCollectionType(List.class, Float.class));
            }

            log.info("Fetching embeddings for text: {}", text);
            EmbeddingsVectorRequest request = EmbeddingsVectorRequest.newBuilder()
                    .setText(text)
                    .build();

            EmbeddingsVectorReply reply = embeddingClient.createEmbeddingsVector(request);
            List<Float> embeddings = reply.getEmbeddingsList();

            // Serialize and store the newly fetched embeddings in the cache
            byte[] serializedEmbeddings = objectMapper.writeValueAsBytes(embeddings);
            String encodedEmbedding = Base64.getEncoder().encodeToString(serializedEmbeddings);
            vectorCache.put(text, encodedEmbedding);

            return embeddings;
        } catch (Exception e) {
            log.error("Error fetching embeddings for text: {}", text, e);
            throw new RuntimeException("Error fetching embeddings for text: " + text, e);
        }
    }

    /**
     * Builds the vector query for a given vector field and embedding.
     *
     * @param vectorFieldInfo The vector field configuration.
     * @param embedding       The vector embeddings.
     * @param topK            The number of top results to fetch.
     * @param boost           The boost factor for the vector query.
     * @return The Solr vector query.
     */
    public String buildVectorQueryForEmbedding(VectorFieldInfo vectorFieldInfo, List<Float> embedding, int topK, float boost) {
        if (vectorFieldInfo == null) {
            throw new IllegalArgumentException("VectorFieldInfo cannot be null");
        }

        validateTopKAndBoost(topK, boost);

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
     * @param boost      The boost factor for the vector query.
     * @return The vector query string.
     */
    private String buildVectorQuery(String field, List<Float> embeddings, int topK, float boost) {
        validateTopKAndBoost(topK, boost);

        String vectorString = embeddings.stream()
                .map(value -> String.format("%.6f", value))
                .collect(Collectors.joining(","));

        String knnQuery = String.format("{!knn f=%s topK=%d v=%s}", field, topK, vectorString);

        if (boost > 0.0f) {
            return String.format("((%s)^%.2f)", knnQuery, boost);
        }
        return knnQuery;
    }

    /**
     * Validates the topK and boost parameters to ensure they are within acceptable ranges.
     *
     * @param topK  The number of top results to fetch.
     * @param boost The boost factor for the vector query.
     */
    private void validateTopKAndBoost(int topK, float boost) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than 0");
        }
        if (boost < 0.0f) {
            throw new IllegalArgumentException("boost must be non-negative");
        }
    }

    public void updateEmbeddingClient(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingClient) {
        this.embeddingClient = embeddingClient;
    }
}