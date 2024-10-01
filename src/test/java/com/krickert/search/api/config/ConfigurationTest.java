package com.krickert.search.api.config;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = {"test"})
public class ConfigurationTest {

    @Inject
    SearchApiConfig searchApiConfig;

    @Inject
    CollectionConfig collectionConfig;

    @Test
    void testConfigurationLoads() {
        // Test SearchApiConfig
        assertNotNull(searchApiConfig, "SearchApiConfig should not be null");
        assertEquals("http://localhost:50401", searchApiConfig.getVectorGrpcChannel(), "vector-grpc-channel should match");

        // Test SolrConfig
        SearchApiConfig.SolrConfig solrConfig = searchApiConfig.getSolr();
        assertNotNull(solrConfig, "SolrConfig should not be null");
        assertEquals("dummy-value-auto-generated", solrConfig.getUrl(), "Solr URL should match");

        // Test Authentication
        SearchApiConfig.SolrConfig.Authentication authentication = solrConfig.getAuthentication();
        assertNotNull(authentication, "Authentication should not be null");
        assertFalse(authentication.isEnabled(), "Authentication should be disabled");
        assertEquals("jwt", authentication.getType(), "Authentication type should match");
        assertEquals("my-client-secret", authentication.getClientSecret(), "Client secret should match");
        assertEquals("my-client-id", authentication.getClientId(), "Client ID should match");
        assertEquals("https://my-token-url.com/oauth2/some-token/v1/token", authentication.getIssuer(), "Issuer should match");
        assertEquals("issuer-auth-id", authentication.getIssuerAuthId(), "Issuer Auth ID should match");
        assertEquals("your-subject", authentication.getSubject(), "Subject should match");
        assertEquals("solr", authentication.getScope(), "Scope should match");
        assertTrue(authentication.isRequireDpop(), "Require DPoP should be true");

        // Test ProxySettings
        SearchApiConfig.SolrConfig.Authentication.ProxySettings proxySettings = authentication.getProxySettings();
        assertNotNull(proxySettings, "ProxySettings should not be null");
        assertTrue(proxySettings.isEnabled(), "Proxy should be enabled");
        assertEquals("localhost", proxySettings.getHost(), "Proxy host should match");
        assertEquals(8080, proxySettings.getPort(), "Proxy port should match");

        // Test CollectionConfig from SolrConfig
        CollectionConfig collectionConfigFromSolrConfig = solrConfig.getCollectionConfig();
        assertNotNull(collectionConfigFromSolrConfig, "CollectionConfig in SolrConfig should not be null");

        // Now test the CollectionConfig
        assertEquals("documents", collectionConfigFromSolrConfig.getCollectionName(), "Collection name should match");
        assertNotNull(collectionConfigFromSolrConfig.getKeywordQueryFields(), "Keyword query fields should not be null");
        assertTrue(collectionConfigFromSolrConfig.getKeywordQueryFields().contains("body"), "Keyword query fields should contain 'body'");
        assertTrue(collectionConfigFromSolrConfig.getKeywordQueryFields().contains("title"), "Keyword query fields should contain 'title'");

        // Test VectorFields
        assertNotNull(collectionConfigFromSolrConfig.getVectorFields(), "Vector fields should not be null");
        assertTrue(collectionConfigFromSolrConfig.getVectorFields().containsKey("body-vector-field"), "Vector fields should contain 'body-vector-field'");
        assertTrue(collectionConfigFromSolrConfig.getVectorFields().containsKey("title-vector-field"), "Vector fields should contain 'title-vector-field'");

        // Test bodyVectorField
        VectorFieldInfo bodyVectorField = collectionConfigFromSolrConfig.getVectorFields().get("body-vector-field");
        assertNotNull(bodyVectorField, "bodyVectorField should not be null");
        assertEquals("body", bodyVectorField.getFieldName(), "Field name should match");
        assertEquals("body-vector", bodyVectorField.getVectorFieldName(), "Vector field name should match");
        assertEquals(VectorFieldType.INLINE, bodyVectorField.getVectorFieldType(), "Vector field type should match");
        assertEquals("http://localhost:50401/vectorizer", bodyVectorField.getVectorGrpcService(), "Vector GRPC service should match");
        assertEquals(Integer.valueOf(10), bodyVectorField.getK(), "K should match");

        // Test titleVectorField
        VectorFieldInfo titleVectorField = collectionConfigFromSolrConfig.getVectorFields().get("title-vector-field");
        assertNotNull(titleVectorField, "titleVectorField should not be null");
        assertEquals("title", titleVectorField.getFieldName(), "Field name should match");
        assertEquals("title-vector", titleVectorField.getVectorFieldName(), "Vector field name should match");
        assertEquals(VectorFieldType.INLINE, titleVectorField.getVectorFieldType(), "Vector field type should match");
        assertEquals("http://localhost:50401/vectorizer", titleVectorField.getVectorGrpcService(), "Vector GRPC service should match");
        assertEquals(Integer.valueOf(10), titleVectorField.getK(), "K should match");
    }
}