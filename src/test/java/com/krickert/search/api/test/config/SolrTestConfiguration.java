package com.krickert.search.api.test.config;

import com.google.common.base.MoreObjects;

public class SolrTestConfiguration {
    private final SolrTestType type;
    private final String collection;
    private final String childCollection;

    public SolrTestConfiguration(SolrTestType type, String collection, String childCollection) {
        this.type = type;
        this.collection = collection;
        this.childCollection = childCollection;
    }

    public SolrTestType getType() {
        return type;
    }

    public String getCollection() {
        return collection;
    }

    public String getChildCollection() {
        return childCollection;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("type", type)
                .add("collection", collection)
                .add("childCollection", childCollection)
                .toString();
    }
}
