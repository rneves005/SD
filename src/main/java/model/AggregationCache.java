package model;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe cache for aggregated statistics.
 * Maps (day, product) -> Aggregate to avoid recomputing expensive aggregations.
 * Uses ReentrantLock for synchronization.
 */
public class AggregationCache {

    /**
     * Aggregate statistics for a product on a specific day.
     */
    public static class Aggregate {
        public long quantity = 0L;
        public double volume = 0.0;
        public double maxPrice = 0.0;

        @Override
        public String toString() {
            return "Aggregate{quantity=" + quantity + ", volume=" + volume + ", maxPrice=" + maxPrice + "}";
        }
    }

    private final Map<Integer, Map<String, Aggregate>> cache;
    private final ReentrantLock lock;

    public AggregationCache() {
        this.cache = new HashMap<>();
        this.lock = new ReentrantLock();
    }

    /**
     * Get cached aggregate for a specific day and product.
     * Returns null if not cached.
     */
    public Aggregate getAggregate(int day, String product) {
        lock.lock();
        try {
            Map<String, Aggregate> perDay = cache.get(day);
            if (perDay != null) {
                return perDay.get(product);
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if aggregate is cached for a specific day and product.
     */
    public boolean hasCached(int day, String product) {
        lock.lock();
        try {
            Map<String, Aggregate> perDay = cache.get(day);
            return perDay != null && perDay.containsKey(product);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Store an aggregate in the cache.
     */
    public void putAggregate(int day, String product, Aggregate aggregate) {
        lock.lock();
        try {
            cache.computeIfAbsent(day, k -> new HashMap<>()).put(product, aggregate);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove all cached entries older than the specified minimum day.
     * Used to invalidate old data when days advance.
     */
    public void removeOlderThan(int minDay) {
        lock.lock();
        try {
            cache.keySet().removeIf(day -> day < minDay);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clear all cached aggregates.
     */
    public void clear() {
        lock.lock();
        try {
            cache.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the number of days currently cached.
     */
    public int size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return "AggregationCache{days=" + cache.size() + "}";
        } finally {
            lock.unlock();
        }
    }
}
