package com.krickert.search.api.test.old;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;

@MicronautTest
class SearchApiTest extends SolrTest {

    private static final Logger log = LoggerFactory.getLogger(SearchApiTest.class);

    @Container
    public static GenericContainer<?> vectorizerContainer = new GenericContainer<>(DockerImageName.parse("krickert/vectorizer:1.0-SNAPSHOT"))
            .withExposedPorts(50401);

    @Inject
    ApplicationContext context;

    @BeforeAll
    @Override
    @SuppressWarnings("resource")
    public void setUp() throws Exception {
        super.setUp(); // Call the parent method to setup Solr

        vectorizerContainer.start();

        String vectorizerHost = vectorizerContainer.getHost();
        Integer vectorizerPort = vectorizerContainer.getMappedPort(50401);


        // Set the dynamic port property in the application context
        context.getEnvironment().addPropertySource(PropertySource.of(
                "test",
                Collections.singletonMap("solr-api.vector-grpc-channel", vectorizerHost + ":" + vectorizerPort)
        ));

        // Logging for debugging
        log.info("Vectorizer running on {}:{}", vectorizerHost, vectorizerPort);

    }
    @Inject
    EmbeddedApplication<?> application;

    @Test
    void testItWorks() {
        Assertions.assertTrue(application.isRunning());
    }

}
