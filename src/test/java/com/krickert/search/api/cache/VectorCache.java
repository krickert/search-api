package com.krickert.search.api.cache;

import jakarta.inject.Singleton;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Singleton
public class VectorCache {
    private static final Logger log = LoggerFactory.getLogger(VectorCache.class);
    private static final String CACHE_FILE_PATH = "src/test/resources/vector_cache.dat";
    private final Map<String, List<Float>> cache;

    public VectorCache() {
        this.cache = Collections.synchronizedMap(new HashMap<>());
        loadCacheFromDisk();
    }

    /**
     * Adds a vector to the cache with the given text as key (hashed using SHA-256).
     *
     * @param text   The text to generate the key.
     * @param vector The vector to be stored.
     */
    public void addVector(String text, List<Float> vector) {
        String key = DigestUtils.sha256Hex(text);
        cache.put(key, new ArrayList<>(vector)); // Ensure the vector is serializable
        saveCacheToDisk();
    }

    /**
     * Retrieves a vector from the cache by text (hashed using SHA-256).
     *
     * @param text The text to generate the key.
     * @return An optional containing the vector if found, or empty if not found.
     */
    public Optional<List<Float>> getVector(String text) {
        String key = DigestUtils.sha256Hex(text);
        return Optional.ofNullable(cache.get(key));
    }

    /**
     * Checks if the cache contains a vector for the given text (hashed using SHA-256).
     *
     * @param text The text to generate the key.
     * @return True if the key exists in the cache, false otherwise.
     */
    public boolean containsVector(String text) {
        String key = DigestUtils.sha256Hex(text);
        return cache.containsKey(key);
    }

    /**
     * Clears the cache.
     */
    public void clearCache() {
        cache.clear();
        saveCacheToDisk();
    }

    /**
     * Loads the cache from disk.
     */
    @SuppressWarnings("unchecked")
    private void loadCacheFromDisk() {
        if (Files.exists(Paths.get(CACHE_FILE_PATH))) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(CACHE_FILE_PATH))) {
                Map<String, List<Float>> loadedCache = (Map<String, List<Float>>) ois.readObject();
                cache.putAll(loadedCache);
                log.info("Cache loaded successfully from disk: {}", CACHE_FILE_PATH);
            } catch (EOFException e) {
                log.warn("Cache file is empty. Starting with an empty cache. File: {}", CACHE_FILE_PATH);
            } catch (IOException e) {
                log.error("IOException occurred while loading cache from disk: {}. Message: {}", CACHE_FILE_PATH, e.getMessage(), e);
            } catch (ClassNotFoundException e) {
                log.error("ClassNotFoundException occurred while loading cache from disk: {}. Message: {}", CACHE_FILE_PATH, e.getMessage(), e);
            }
        } else {
            log.info("No cache file found. Starting with an empty cache. File: {}", CACHE_FILE_PATH);
        }
    }

    /**
     * Saves the cache to disk.
     */
    private void saveCacheToDisk() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(CACHE_FILE_PATH))) {
            oos.writeObject(cache);
            log.info("Cache saved successfully to disk: {}", CACHE_FILE_PATH);
        } catch (IOException e) {
            log.error("IOException occurred while saving cache to disk: {}. Message: {}", CACHE_FILE_PATH, e.getMessage(), e);
        }
    }
}
