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

import javax.annotation.PreDestroy;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.apache.commons.lang3.StringEscapeUtils.escapeJson;

@Singleton
public class SolrService {

    private static final Logger log = LoggerFactory.getLogger(SolrService.class);

    private final SolrClient solrClient;
    private final String solrUrl;

    public SolrService(SearchApiConfig config,
                       @Value("${search-api.solr.url}") String solrUrl) {
        final String urlToUse;
        if (StringUtils.isNotEmpty(solrUrl)) {
            urlToUse = solrUrl;
        } else {
            urlToUse = config.getSolr().getUrl();
        }
        this.solrUrl = urlToUse;
        this.solrClient = new Http2SolrClient.Builder(urlToUse).build();
        log.info("Initialized SolrClient with URL from @Value: {}", urlToUse);

    }

    /**
     * Executes a Solr query using POST method to prevent header size overrun.
     *
     * @param collection  The Solr collection name.
     * @param queryParams The query parameters as a map of parameter names to their values.
     *                    Each parameter can have multiple values.
     * @return The Solr QueryResponse.
     * @throws SolrServerException If a Solr error occurs.
     * @throws IOException         If an I/O error occurs.
     */
    public QueryResponse query(String collection, Map<String, List<String>> queryParams) throws SolrServerException, IOException {
        ModifiableSolrParams params = new ModifiableSolrParams();

        // Add all parameters, supporting multiple values for the same key
        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            params.add(key, values.toArray(new String[0]));
        }

        // Create a QueryRequest with the parameters
        QueryRequest request = new QueryRequest(params);
        request.setMethod(SolrRequest.METHOD.POST); // Set HTTP method to POST

        log.debug("Executing Solr POST query to collection '{}': {}", collection, params);
        if (log.isDebugEnabled()) {
            String curlCommand = generateSolrSelectPostCurl(solrUrl, collection, params);
            log.debug("\n\n*** You can test with this curl command ***\n\n{}\n\n", curlCommand);
        }
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
    @PreDestroy
    public void close() throws IOException {
        solrClient.close();
        log.info("SolrClient has been closed.");
    }

    /**
     * Generate a cURL command for Solr in JSON format.
     *
     * @param baseUrl    The Solr base URL.
     * @param collection The Solr collection.
     * @param params     The Solr query parameters.
     * @return The formatted cURL command string.
     */
    public static String generateSolrSelectPostCurl(String baseUrl, String collection, ModifiableSolrParams params) {
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{\n  \"params\": {\n");

        List<String> paramList = new ArrayList<>();
        for (String paramName : params.getParameterNames()) {
            String[] values = params.getParams(paramName);
            if (values != null) {
                for (String value : values) {
                    paramList.add("    \"" + paramName + "\": \"" + value + "\"");
                }
            }
        }

        jsonBuilder.append(String.join(",\n", paramList));
        jsonBuilder.append("\n  }\n}");

        // Construct the cURL command with the JSON body
        return String.format(
                "curl -X POST -H 'Content-Type: application/json' -d '%s' '%s/%s/query'",
                jsonBuilder.toString(), baseUrl, collection
        );
    }
}