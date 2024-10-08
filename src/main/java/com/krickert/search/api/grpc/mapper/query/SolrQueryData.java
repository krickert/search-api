package com.krickert.search.api.grpc.mapper.query;

import java.util.Map;
import java.util.List;

public class SolrQueryData {
    private final Map<String, List<String>> queryParams;

    public SolrQueryData(Map<String, List<String>> queryParams) {
        this.queryParams = queryParams;
    }

    public Map<String, List<String>> getQueryParams() {
        return queryParams;
    }
}