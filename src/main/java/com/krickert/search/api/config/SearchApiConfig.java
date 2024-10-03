package com.krickert.search.api.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;

import java.util.Collection;

@ConfigurationProperties("search-api")
@Introspected
public class SearchApiConfig {

    @JsonProperty("solr")
    private SolrConfig solr;

    @JsonProperty("vector-default")
    private VectorDefault vectorDefault;

    public SolrConfig getSolr() {
        return solr;
    }

    public void setSolr(SolrConfig solr) {
        this.solr = solr;
    }

    public VectorDefault getVectorDefault() {
        return vectorDefault;
    }

    public void setVectorDefault(VectorDefault vectorDefault) {
        this.vectorDefault = vectorDefault;
    }

    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("solr", solr)
                .add("vectorDefault", vectorDefault)
                .toString();
    }

    @ConfigurationProperties("solr")
    @Introspected
    public static class SolrConfig {

        private final CollectionConfig collectionConfig;

        @JsonProperty("default-search")
        private DefaultSearch defaultSearch;

        @JsonProperty("url")
        private String url;

        @JsonProperty("authentication")
        private Authentication authentication;

        // Constructor for injection
        public SolrConfig(CollectionConfig collectionConfig) {
            this.collectionConfig = collectionConfig;
        }

        // getters and setters
        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Authentication getAuthentication() {
            return authentication;
        }

        public void setAuthentication(Authentication authentication) {
            this.authentication = authentication;
        }

        public CollectionConfig getCollectionConfig() {
            return collectionConfig;
        }

        public DefaultSearch getDefaultSearch() {
            return defaultSearch;
        }
        public void setDefaultSearch(DefaultSearch defaultSearch) {
            this.defaultSearch = defaultSearch;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("url", url)
                    .add("authentication", authentication)
                    .add("collectionConfig", collectionConfig)
                    .toString();
        }

        @ConfigurationProperties("authentication")
        @Introspected
        public static class Authentication {

            @JsonProperty("enabled")
            private boolean enabled;

            @JsonProperty("type")
            private String type;

            @JsonProperty("client-secret")
            private String clientSecret;

            @JsonProperty("client-id")
            private String clientId;

            @JsonProperty("issuer")
            private String issuer;

            @JsonProperty("issuerAuthId")
            private String issuerAuthId;

            @JsonProperty("subject")
            private String subject;

            @JsonProperty("scope")
            private String scope;

            @JsonProperty("require-dpop")
            private boolean requireDpop;

            @JsonProperty("proxy-settings")
            private ProxySettings proxySettings;

            // Getters and Setters
            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
            }

            public String getClientSecret() {
                return clientSecret;
            }

            public void setClientSecret(String clientSecret) {
                this.clientSecret = clientSecret;
            }

            public String getClientId() {
                return clientId;
            }

            public void setClientId(String clientId) {
                this.clientId = clientId;
            }

            public String getIssuer() {
                return issuer;
            }

            public void setIssuer(String issuer) {
                this.issuer = issuer;
            }

            public String getIssuerAuthId() {
                return issuerAuthId;
            }

            public void setIssuerAuthId(String issuerAuthId) {
                this.issuerAuthId = issuerAuthId;
            }

            public String getSubject() {
                return subject;
            }

            public void setSubject(String subject) {
                this.subject = subject;
            }

            public String getScope() {
                return scope;
            }

            public void setScope(String scope) {
                this.scope = scope;
            }

            public boolean isRequireDpop() {
                return requireDpop;
            }

            public void setRequireDpop(boolean requireDpop) {
                this.requireDpop = requireDpop;
            }

            public ProxySettings getProxySettings() {
                return proxySettings;
            }

            public void setProxySettings(ProxySettings proxySettings) {
                this.proxySettings = proxySettings;
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                        .add("enabled", enabled)
                        .add("type", type)
                        .add("clientSecret", clientSecret)
                        .add("clientId", clientId)
                        .add("issuer", issuer)
                        .add("issuerAuthId", issuerAuthId)
                        .add("subject", subject)
                        .add("scope", scope)
                        .add("requireDpop", requireDpop)
                        .add("proxySettings", proxySettings)
                        .toString();
            }

            @ConfigurationProperties("proxy-settings")
            @Introspected
            public static class ProxySettings {

                @JsonProperty("enabled")
                private boolean enabled;

                @JsonProperty("host")
                private String host;

                @JsonProperty("port")
                private int port;

                // Getters and Setters
                public boolean isEnabled() {
                    return enabled;
                }

                public void setEnabled(boolean enabled) {
                    this.enabled = enabled;
                }

                public String getHost() {
                    return host;
                }

                public void setHost(String host) {
                    this.host = host;
                }

                public int getPort() {
                    return port;
                }

                public void setPort(int port) {
                    this.port = port;
                }

                @Override
                public String toString() {
                    return MoreObjects.toStringHelper(this)
                            .add("enabled", enabled)
                            .add("host", host)
                            .add("port", port)
                            .toString();
                }
            }
        }

        @ConfigurationProperties("default-search")
        @Introspected
        public static class DefaultSearch {
            Integer rows;
            String sort;
            String searchType;
            Boolean debug;
            Collection<String> includeFields;
            Collection<String> excludeFields;

            public Integer getRows() {
                return rows;
            }

            public void setRows(Integer rows) {
                this.rows = rows;
            }

            public String getSort() {
                return sort;
            }

            public void setSort(String sort) {
                this.sort = sort;
            }

            public String getSearchType() {
                return searchType;
            }

            public void setSearchType(String searchType) {
                this.searchType = searchType;
            }

            public Boolean getDebug() {
                return debug;
            }

            public void setDebug(Boolean debug) {
                this.debug = debug;
            }

            public Collection<String> getIncludeFields() {
                return includeFields;
            }

            public void setIncludeFields(Collection<String> includeFields) {
                this.includeFields = includeFields;
            }

            public Collection<String> getExcludeFields() {
                return excludeFields;
            }
            public void setExcludeFields(Collection<String> excludeFields) {
                this.excludeFields = excludeFields;
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                        .add("rows", rows)
                        .add("sort", sort)
                        .add("searchType", searchType)
                        .add("debug", debug)
                        .toString();
            }
        }
    }

    @ConfigurationProperties("vector-default")
    @Introspected
    public static class VectorDefault {
        private String vectorGrpcChannel;
        private String vectorGrpcDisplayName;

        public String getVectorGrpcChannel() {
            return vectorGrpcChannel;
        }
        public void setVectorGrpcChannel(String vectorGrpcChannel) {
            this.vectorGrpcChannel = vectorGrpcChannel;
        }

        public String getVectorGrpcDisplayName() {
            return vectorGrpcDisplayName;
        }
        public void setVectorGrpcDisplayName(String vectorGrpcDisplayName) {
            this.vectorGrpcDisplayName = vectorGrpcDisplayName;
        }
        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("vectorGrpcChannel", vectorGrpcChannel)
                    .add("vectorGrpcDisplayName", vectorGrpcDisplayName)
                    .toString();
        }
    }
}