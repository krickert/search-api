package com.krickert.search.api.solr;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.SingleFileStoreConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Singleton
public class EmbeddedInfinispanCache {

    private static final String CACHE_NAME = "vectorCache";
    private static EmbeddedCacheManager embeddedCacheManager;

    public EmbeddedInfinispanCache(@Value("${search-api.vector-default.vector-cache-location:infinispan-cache-store}") String cacheLocation) {
        try {
            if (embeddedCacheManager == null) {
                // Set up a DefaultCacheManager using Infinispan's embedded mode
                Path path = Paths.get(cacheLocation);
                if (!Files.exists(path)) {
                    Files.createDirectories(path);
                }

                embeddedCacheManager = new DefaultCacheManager();

                // Define configuration for the cache
                embeddedCacheManager.defineConfiguration(CACHE_NAME, new ConfigurationBuilder()
                        .memory().maxCount(1000) // Limit the number of entries in the cache
                        .expiration().lifespan(1, TimeUnit.HOURS) // Set expiry time for cached entries
                        .persistence()
                        .passivation(false) // Avoid removing entries from memory unless required
                        .addStore(SingleFileStoreConfigurationBuilder.class) // Add single file store for persistence
                        .location(cacheLocation) // Directory to store the data
                        .build());

                // Start the cache manager
                embeddedCacheManager.start();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Infinispan as an embedded cache", e);
        }
    }

    public org.infinispan.Cache<String, String> getCache() {
        return embeddedCacheManager.getCache(CACHE_NAME);
    }

    public void stopCache() {
        if (embeddedCacheManager != null) {
            embeddedCacheManager.stop();
        }
    }
}
