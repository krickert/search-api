package com.krickert.search.api.solr;

import jakarta.inject.Singleton;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.SingleFileStoreConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.spi.CachingProvider;
import java.util.concurrent.TimeUnit;

@Singleton
public class EmbeddedInfinispanCache {

    private static final String CACHE_NAME = "vectorCache";
    private static CacheManager cacheManager;
    private static Cache<String, String> vectorCache;

    public EmbeddedInfinispanCache() {
        try {
            if (cacheManager == null) {
                // Set up a DefaultCacheManager using Infinispan's embedded mode
                EmbeddedCacheManager embeddedCacheManager = new DefaultCacheManager();

                // Define configuration for the cache
                embeddedCacheManager.defineConfiguration(CACHE_NAME, new ConfigurationBuilder()
                        .memory().maxCount(1000) // Limit the number of entries in the cache
                        .expiration().lifespan(1, TimeUnit.HOURS) // Set expiry time for cached entries
                        .persistence().addStore(SingleFileStoreConfigurationBuilder.class) // Add single file store for persistence
                        .location("infinispan-cache-store") // Directory to store the data
                        .build());

                // Initialize JCache caching provider
                CachingProvider cachingProvider = Caching.getCachingProvider();
                cacheManager = cachingProvider.getCacheManager();
            }

            if (vectorCache == null) {
                // Create JCache configuration and register it if it does not exist
                if (cacheManager.getCache(CACHE_NAME) == null) {
                    MutableConfiguration<String, String> jCacheConfig = new MutableConfiguration<>();
                    vectorCache = cacheManager.createCache(CACHE_NAME, jCacheConfig);
                } else {
                    vectorCache = cacheManager.getCache(CACHE_NAME);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Infinispan as an embedded cache", e);
        }
    }

    public Cache<String, String> getCache() {
        return vectorCache;
    }
}
