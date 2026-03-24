package model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe storage for time series data with LRU eviction policy.
 * Manages in-memory series (LinkedHashMap with access order) and coordinates with disk persistence.
 * Uses ReentrantLock for synchronization.
 */
public class TimeSeriesStore {
    private final int maxInMemorySeries;
    private final Path storageDir;
    private final LinkedHashMap<Integer, DaySeries> inMemorySeries;
    private final ReentrantLock lock;
    private int currentDay;

    /**
     * Create a new TimeSeriesStore.
     *
     * @param maxInMemorySeries Maximum number of past days to keep in memory (excluding current day)
     * @param storageDir Directory for persistent storage
     * @param currentDay The current day number
     */
    public TimeSeriesStore(int maxInMemorySeries, Path storageDir, int currentDay) {
        this.maxInMemorySeries = Math.max(0, maxInMemorySeries);
        this.storageDir = storageDir;
        this.currentDay = currentDay;
        this.lock = new ReentrantLock();

        // LinkedHashMap with access-order (true) for LRU eviction
        this.inMemorySeries = new LinkedHashMap<Integer, DaySeries>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, DaySeries> eldest) {
                // Evict when we exceed allowed in-memory series (keep current day)
                return size() > TimeSeriesStore.this.maxInMemorySeries + 1
                       && eldest.getKey() != TimeSeriesStore.this.currentDay;
            }
        };

        // Create series for the initial current day
        inMemorySeries.put(currentDay, new DaySeries(currentDay));
    }

    /**
     * Update the current day number (used when advancing to next day).
     */
    public void setCurrentDay(int currentDay) {
        lock.lock();
        try {
            this.currentDay = currentDay;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get current day number.
     */
    public int getCurrentDay() {
        lock.lock();
        try {
            return currentDay;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Create a new empty series for a specific day.
     */
    public void createSeriesForDay(int day) {
        lock.lock();
        try {
            inMemorySeries.put(day, new DaySeries(day));
            evictIfNeeded();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Add an event to a specific day's series.
     * Automatically persists the event to disk.
     */
    public void addEventToDay(int day, Event event) throws IOException {
        lock.lock();
        try {
            DaySeries series = inMemorySeries.get(day);
            if (series == null) {
                series = new DaySeries(day);
                inMemorySeries.put(day, series);
            }
            series.addEvent(event);

            // Persist event to disk (append mode)
            series.appendEventToDisk(storageDir, event);

            evictIfNeeded();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get a thread-safe snapshot of events for a specific day.
     * Returns null if day is not in memory.
     */
    public List<Event> getSnapshotForDay(int day) {
        lock.lock();
        try {
            DaySeries series = inMemorySeries.get(day); // this updates LRU access order
            if (series != null) {
                return series.getEventsSnapshot();
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if a day's series is currently in memory.
     */
    public boolean isInMemory(int day) {
        lock.lock();
        try {
            return inMemorySeries.containsKey(day);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Load a series from disk (if not already in memory).
     * Returns the loaded series or null if file doesn't exist.
     */
    public DaySeries loadSeriesFromDisk(int day) throws IOException {
        // First check if already in memory
        lock.lock();
        try {
            if (inMemorySeries.containsKey(day)) {
                return inMemorySeries.get(day);
            }
        } finally {
            lock.unlock();
        }

        // Load from disk (outside lock to avoid blocking during I/O)
        DaySeries series = DaySeries.loadFromDisk(storageDir, day);

        // Store in memory if loaded successfully
        if (series != null && !series.getEvents().isEmpty()) {
            lock.lock();
            try {
                inMemorySeries.put(day, series);
                evictIfNeeded();
            } finally {
                lock.unlock();
            }
        }

        return series;
    }

    /**
     * Evict oldest series from memory if size exceeds limit.
     * Must be called while holding the lock.
     */
    private void evictIfNeeded() {
        // LinkedHashMap's removeEldestEntry already handles this,
        // but we can also manually evict if needed
        while (inMemorySeries.size() > maxInMemorySeries + 1) {
            Integer oldest = inMemorySeries.keySet().iterator().next();
            if (oldest == currentDay) {
                break; // safety: never evict current day
            }
            inMemorySeries.remove(oldest);
            // Note: data is already persisted to disk via appendEventToDisk
        }
    }

    /**
     * Get the number of series currently in memory.
     */
    public int getInMemoryCount() {
        lock.lock();
        try {
            return inMemorySeries.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return "TimeSeriesStore{currentDay=" + currentDay + ", inMemory=" + inMemorySeries.size() + "}";
        } finally {
            lock.unlock();
        }
    }
}
