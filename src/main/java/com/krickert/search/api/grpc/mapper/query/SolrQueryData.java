package com.krickert.search.api.grpc.mapper.query;

import java.util.List;
import java.util.Map;

public class SolrQueryData {
    private final Map<String, List<String>> queryParams;

    public SolrQueryData(Map<String, List<String>> queryParams) {
        this.queryParams = queryParams;
    }

    public Map<String, List<String>> getQueryParams() {
        return queryParams;
    }
}