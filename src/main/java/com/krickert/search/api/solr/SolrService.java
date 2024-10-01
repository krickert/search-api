package com.krickert.search.api.solr;

import com.krickert.search.api.config.SearchApiConfig;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Singleton;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.io.IOException;

@Singleton
public class SolrService {

    private static final Logger log = LoggerFactory.getLogger(SolrService.class);

    private final SolrClient solrClient;

    public SolrService(SearchApiConfig config,
                       @Value("${search-api.solr.url}") String solrUrl) {
        if (StringUtils.isNotEmpty(solrUrl)) {
            this.solrClient = new Http2SolrClient.Builder(solrUrl).build();
            log.info("Initialized SolrClient with URL from @Value: {}", solrUrl);
        } else {
            this.solrClient = new Http2SolrClient.Builder(config.getSolr().getUrl()).build();
            log.info("Initialized SolrClient with URL from SearchApiConfig: {}", config.getSolr().getUrl());
        }
    }

    /**
     * Executes a Solr query using POST method to prevent header size overrun.
     *
     * @param collection  The Solr collection name.
     * @param queryParams The query parameters.
     * @return The Solr QueryResponse.
     * @throws SolrServerException If a Solr error occurs.
     * @throws IOException         If an I/O error occurs.
     */
    public QueryResponse query(String collection, Map<String, String> queryParams) throws SolrServerException, IOException {
        ModifiableSolrParams params = new ModifiableSolrParams();
        queryParams.forEach(params::set);

        // Create a QueryRequest with the parameters
        QueryRequest request = new QueryRequest(params);
        request.setMethod(SolrRequest.METHOD.POST); // Set HTTP method to POST

        log.debug("Executing Solr POST query to collection '{}': {}", collection, params);

        // Execute the request and return the response
        QueryResponse response = request.process(solrClient, collection);

        log.debug("Received Solr response: {}", response);

        return response;
    }

    /**
     * Closes the SolrClient instance.
     *
     * @throws IOException If an I/O error occurs during closing.
     */
    public void close() throws IOException {
        solrClient.close();
        log.info("SolrClient has been closed.");
    }
}