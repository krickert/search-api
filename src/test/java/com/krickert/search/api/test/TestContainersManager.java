package com.krickert.search.api.test;

import jakarta.inject.Singleton;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

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
            String command = "solr -c -f";
            this.setCommand(command);
            this.waitStrategy =
                    new LogMessageWaitStrategy()
                            .withRegEx(".*Server Started.*")
                            .withStartupTimeout(Duration.of(180, ChronoUnit.SECONDS));
        }
    };

    private static final GenericContainer<?> vectorizerContainer = new GenericContainer<>(DockerImageName.parse(VECTORIZER_IMAGE))
            .withExposedPorts(50401)
            .withStartupTimeout(Duration.of(180, ChronoUnit.SECONDS));

    private static final GenericContainer<?> chunkerContainer = new GenericContainer<>(DockerImageName.parse(CHUNKER_IMAGE))
            .withExposedPorts(50402)
            .withStartupTimeout(Duration.of(180, ChronoUnit.SECONDS));

    static {
        try {
            // Start all containers
            solrContainer.start();
            vectorizerContainer.start();
            chunkerContainer.start();

            log.info("Solr running on {}:{}", solrContainer.getHost(), solrContainer.getMappedPort(8983));
            log.info("Vectorizer running on {}:{}", vectorizerContainer.getHost(), vectorizerContainer.getMappedPort(50401));
            log.info("Chunker running on {}:{}", chunkerContainer.getHost(), chunkerContainer.getMappedPort(50402));

            // Set system properties for container URLs to be accessible in the tests
            System.setProperty("search-api.solr.url", "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr");
            System.setProperty("vectorizer.url", "http://" + vectorizerContainer.getHost() + ":" + vectorizerContainer.getMappedPort(50401));
            System.setProperty("chunker.url", "http://" + chunkerContainer.getHost() + ":" + chunkerContainer.getMappedPort(50402));

        } catch (Exception e) {
            log.error("Error initializing containers: {}", e.getMessage(), e);
            throw new RuntimeException(e);
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

    public static String getChunkerUrl() {
        return "http://" + chunkerContainer.getHost() + ":" + chunkerContainer.getMappedPort(50402);
    }

    // Cleanup method to stop containers after the entire test suite
    public static void stopContainers() {
        if (solrContainer != null && solrContainer.isRunning()) {
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
