package com.antitoxicity;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class UsernameCache {
    private final File cacheFile;
    private final Map<String, String> cache; // username -> reason (empty string = safe)
    private final Logger logger;

    public UsernameCache(File dataFolder, Logger logger) {
        this.cacheFile = new File(dataFolder, "username_cache.dat");
        this.logger = logger;
        this.cache = new HashMap<>();
        loadCache();
    }

    private void loadCache() {
        if (!cacheFile.exists()) {
            logger.info("[ATOX] Username cache file not found, creating new one");
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cacheFile))) {
            @SuppressWarnings("unchecked")
            Map<String, String> loaded = (Map<String, String>) ois.readObject();
            cache.putAll(loaded);
            logger.info("[ATOX] Loaded " + cache.size() + " entries from username cache");
        } catch (IOException | ClassNotFoundException e) {
            logger.warning("[ATOX] Failed to load username cache: " + e.getMessage());
        }
    }

    public void saveCache() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(cacheFile))) {
            oos.writeObject(cache);
            logger.info("[ATOX] Saved " + cache.size() + " entries to username cache");
        } catch (IOException e) {
            logger.severe("[ATOX] Failed to save username cache: " + e.getMessage());
        }
    }

    /**
     * Returns cached reason if username was analyzed before.
     * @param username the player name
     * @return reason if offensive, empty string if safe, null if not cached
     */
    public String getCachedResult(String username) {
        return cache.get(username.toLowerCase());
    }

    /**
     * Cache the analysis result for a username.
     * @param username the player name
     * @param reason offensive reason, or empty string if safe
     */
    public void cacheResult(String username, String reason) {
        cache.put(username.toLowerCase(), reason != null ? reason : "");
    }

    /**
     * Check if username has been analyzed before.
     * @param username the player name
     * @return true if cached, false otherwise
     */
    public boolean isCached(String username) {
        return cache.containsKey(username.toLowerCase());
    }

    /**
     * Get total number of cached usernames.
     */
    public int size() {
        return cache.size();
    }
}
