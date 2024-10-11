package com.krickert.search.api.test;

import com.krickert.search.service.ChunkServiceGrpc;
import com.krickert.search.service.EmbeddingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Bean;
import jakarta.inject.Singleton;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Singleton
public final class TestContainersManager {

    private static final Logger log = LoggerFactory.getLogger(TestContainersManager.class);

    private static final String SOLR_IMAGE = "solr:9.7.0";
    private static final String VECTORIZER_IMAGE = "krickert/vectorizer:1.0-SNAPSHOT";
    private static final String CHUNKER_IMAGE = "krickert/chunker:1.0-SNAPSHOT";

    private static final SolrContainer solrContainer = new SolrContainer(DockerImageName.parse(SOLR_IMAGE)) {
        @Override
        protected void configure() {
            this.addExposedPort(8983);
            this.withZookeeper(true);
            this.withAccessToHost(true);
            String command = "solr -c -f";
            this.setCommand(command);
            this.waitStrategy =
                    new LogMessageWaitStrategy()
                            .withRegEx(".*Server Started.*")
                            .withStartupTimeout(Duration.of(180, ChronoUnit.SECONDS));
        }
    };

    private static final GenericContainer<?> vectorizerContainer =
            new GenericContainer<>(DockerImageName.parse(VECTORIZER_IMAGE))
                    .withExposedPorts(50401, 60401)
                    .withEnv("JAVA_OPTS", "-Xmx5g")
                    .withEnv("MICRONAUT_SERVER_NETTY_THREADS", "1000")
                    .withEnv("MICRONAUT_EXECUTORS_DEFAULT_THREADS", "500")
                    .withStartupTimeout(Duration.of(180, ChronoUnit.SECONDS));

    private static final GenericContainer<?> chunkerContainer = new GenericContainer<>(DockerImageName.parse(CHUNKER_IMAGE))
            .withExposedPorts(50403, 60403)
            .withEnv("JAVA_OPTS", "-Xmx5g") // Allocate 5GB for the JVM
            .withEnv("MICRONAUT_SERVER_NETTY_THREADS", "1000")
            .withEnv("MICRONAUT_EXECUTORS_DEFAULT_THREADS", "500")
            .withStartupTimeout(Duration.of(300, ChronoUnit.SECONDS)) // Increase startup timeout to 300 seconds
            .waitingFor(Wait.forHttp("/health") // Wait for a health check endpoint
                    .forPort(60403)
                    .withStartupTimeout(Duration.of(300, ChronoUnit.SECONDS)))
            .withLogConsumer(new Slf4jLogConsumer(log)) // Log output for better debugging
            .waitingFor(Wait.forListeningPort()); // Also wait for the port to be ready

    private static final AtomicBoolean containersStarted = new AtomicBoolean(false);

    public static void startContainers() {
        if (containersStarted.compareAndSet(false, true)) {
            try {
                // Start all containers
                solrContainer.start();
                vectorizerContainer.start();
                chunkerContainer.start();

                log.info("Solr running on {}:{}", solrContainer.getHost(), solrContainer.getMappedPort(8983));
                log.info("Vectorizer running on {}:{}", vectorizerContainer.getHost(), vectorizerContainer.getMappedPort(50401));
                log.info("Chunker running on {}:{}", chunkerContainer.getHost(), chunkerContainer.getMappedPort(50403));

                // Set system properties for container URLs to be accessible in the tests
                System.setProperty("search-api.solr.url", "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr");
                System.setProperty("search-api.vector-default.vector-grpc-channel", "http://" + vectorizerContainer.getHost() + ":" + vectorizerContainer.getMappedPort(50401));
                System.setProperty("chunker.url", "http://" + chunkerContainer.getHost() + ":" + chunkerContainer.getMappedPort(50403));
            } catch (Exception e) {
                log.error("Error initializing containers: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
    }

    // Private constructor to prevent instantiation
    private TestContainersManager() {}

    // Public method to get SolrClient instance
    public static Http2SolrClient createSolrClient() {
        return new Http2SolrClient.Builder("http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr").build();
    }

    public static String getVectorizerUrl() {
        return "http://" + vectorizerContainer.getHost() + ":" + vectorizerContainer.getMappedPort(50401);
    }

    public static String getSolrBaseUrl() {
        return  "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr";
    }

    @Bean
    EmbeddingServiceGrpc.EmbeddingServiceBlockingStub createEmbeddingServiceBlockingStub() {
        int vectorizerPort = vectorizerContainer.getMappedPort(50401);
        ManagedChannel embeddingChannel = ManagedChannelBuilder.forAddress("localhost", vectorizerPort)
                .usePlaintext()
                .build();
        return EmbeddingServiceGrpc.newBlockingStub(embeddingChannel);
    }

    public static String getChunkerUrl() {
        return "http://" + chunkerContainer.getHost() + ":" + chunkerContainer.getMappedPort(50403);
    }

    @Bean
    ChunkServiceGrpc.ChunkServiceBlockingStub createChunkServiceBlockingStub() {
        int chunkerPort = chunkerContainer.getMappedPort(50403);
        ManagedChannel embeddingChannel = ManagedChannelBuilder.forAddress("localhost", chunkerPort)
                .usePlaintext()
                .build();
        return ChunkServiceGrpc.newBlockingStub(embeddingChannel);
    }

    // Cleanup method to stop containers after the entire test suite
    public static void stopContainers() {
        if (solrContainer.isRunning()) {
            solrContainer.stop();
        }
        if (vectorizerContainer != null && vectorizerContainer.isRunning()) {
            vectorizerContainer.stop();
        }
        if (chunkerContainer != null && chunkerContainer.isRunning()) {
            chunkerContainer.stop();
        }
    }
}