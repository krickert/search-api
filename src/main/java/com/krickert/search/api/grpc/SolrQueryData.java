package com.krickert.search.api.grpc;

import java.util.Map;
import java.util.List;

public class SolrQueryData {
    private final Map<String, List<String>> queryParams;
    private final String fl;

    public SolrQueryData(Map<String, List<String>> queryParams, String fl) {
        this.queryParams = queryParams;
        this.fl = fl;
    }

    public Map<String, List<String>> getQueryParams() {
        return queryParams;
    }

    public String getFl() {
        return fl;
    }
}