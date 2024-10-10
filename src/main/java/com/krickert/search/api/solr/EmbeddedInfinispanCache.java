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
import java.util.List;
import java.util.concurrent.TimeUnit;

@Singleton
public class EmbeddedInfinispanCache {

    private CacheManager cacheManager;

    public EmbeddedInfinispanCache() {
        try {
            // Set up a DefaultCacheManager using Infinispan's embedded mode
            EmbeddedCacheManager embeddedCacheManager = new DefaultCacheManager();
            
            // Define configuration for the cache
            embeddedCacheManager.defineConfiguration("vectorCache", new ConfigurationBuilder()
                    .memory().maxCount(1000) // Limit the number of entries in the cache
                    .expiration().lifespan(1, TimeUnit.HOURS) // Set expiry time for cached entries
                    .persistence().addStore(SingleFileStoreConfigurationBuilder.class) // Add single file store for persistence
                    .location("infinispan-cache-store") // Directory to store the data
                    .build());

            // Initialize JCache caching provider
            CachingProvider cachingProvider = Caching.getCachingProvider();
            this.cacheManager = cachingProvider.getCacheManager();

            // Create JCache configuration and register it
            MutableConfiguration<String, List<Float>> jCacheConfig = new MutableConfiguration<>();
            this.cacheManager.createCache("vectorCache", jCacheConfig);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Infinispan as an embedded cache", e);
        }
    }

    public Cache<String, String> getCache() {
        return cacheManager.getCache("vectorCache");
    }
}
