package com.krickert.search.api.test.old;

import jakarta.inject.Singleton;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.slf4j.Logger;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Singleton
public final class SolrTestHelper {

    static Logger log = org.slf4j.LoggerFactory.getLogger(SolrTestHelper.class);
    static String solrBaseUrl;

    @Container
    static final SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.7.0")) {
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

    SolrTestHelper() throws Exception {
        setUp();
        solrBaseUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr";
    }

    public static void setUp() throws Exception {
        solrContainer.start();
        System.setProperty("search-api.solr.url", solrBaseUrl);
        log.info("Solr running on {}:{}", solrContainer.getHost(), solrContainer.getMappedPort(8983));
        log.info("Solr client can be accessed at {}", solrBaseUrl);
    }


    public Http2SolrClient createSolrClient() {
        return new Http2SolrClient.Builder(solrBaseUrl).build();
    }


}
