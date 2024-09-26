package com.krickert.search.api.solr;

import com.krickert.search.api.config.SearchApiConfig;
import jakarta.inject.Singleton;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.params.ModifiableSolrParams;

import java.util.Map;

@Singleton
public class SolrService {

    private final SolrClient solrClient;

    public SolrService(SearchApiConfig config) {
        this.solrClient = new Http2SolrClient.Builder(config.getSolr().getCollectionConfig().getCollectionName()).build();
    }

    public QueryResponse query(String collection, Map<String, String> queryParams) throws Exception {
        ModifiableSolrParams params = new ModifiableSolrParams();
        queryParams.forEach(params::set);
        return solrClient.query(collection, params);
    }

    public void close() throws Exception {
        solrClient.close();
    }
}