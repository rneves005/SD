package server;

import model.AggregationCache;
import model.DaySeries;
import model.Event;
import model.TimeSeriesStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


/**
* Central server state.
* Generate authentication, time series, and notifications.
*/
public class ServerState {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition dayChanged = lock.newCondition();
    private final Condition simultaneousCond = lock.newCondition();
    private final Condition consecutiveCond = lock.newCondition();

    private final AuthManager authManager;
    private final TimeSeriesStore timeSeriesStore;
    private final AggregationCache aggregationCache;

    private int currentDay = 0;
    private final int maxDays;
    private final Path storageDir;

    // State for notifications current day
    private final Set<String> currentDaySoldProducts = new HashSet<>();
    private String lastProduct = null;
    private int consecutiveCount = 0;

    public ServerState(int maxDays, int maxInMemorySeries, Path storageDir) throws IOException {
        if (maxInMemorySeries < 0 || maxDays < 1) {
            throw new IllegalArgumentException("invalid params");
        }
        this.maxDays = maxDays;
        this.storageDir = storageDir;

        if (!Files.exists(storageDir)) {
            Files.createDirectories(storageDir);
        }

        this.authManager = new AuthManager();
        this.timeSeriesStore = new TimeSeriesStore(maxInMemorySeries, storageDir, currentDay);
        this.aggregationCache = new AggregationCache();
    }

    //  Authentication methods

    public void addUser(String username, String password) {
        authManager.addUser(username, password);
    }

    public boolean userAlreadyExists(String username) {
        return authManager.userExists(username);
    }

    public boolean authenticateUser(String username, String password) {
        return authManager.authenticate(username, password);
    }

    public void logoutUser(String username) {
        authManager.logout(username);
    }

    public boolean isUserLoggedIn(String username) {
        return authManager.isLoggedIn(username);
    }

    // Temporal series methods

    // Next day operation 
    public void nextDay() {
        lock.lock();
        try {
            currentDay++;
            timeSeriesStore.setCurrentDay(currentDay);

            currentDaySoldProducts.clear();
            lastProduct = null;
            consecutiveCount = 0;

            timeSeriesStore.createSeriesForDay(currentDay);

            aggregationCache.removeOlderThan(currentDay - maxDays);

            dayChanged.signalAll();
            simultaneousCond.signalAll();
            consecutiveCond.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public int getCurrentDay() {
        lock.lock();
        try {
            return currentDay;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Add an event to the current day's series.
     */
    public void addEvent(String product, int quantity, double price) throws IOException {
        lock.lock();
        try {
            Event event = new Event(product, quantity, price);

            timeSeriesStore.addEventToDay(currentDay, event);

            boolean newlySold = currentDaySoldProducts.add(product);
            if (product.equals(lastProduct)) {
                consecutiveCount++;
            } else {
                lastProduct = product;
                consecutiveCount = 1;
            }

            // Acordar threads à espera
            if (newlySold) {
                simultaneousCond.signalAll();
            }
            consecutiveCond.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // Agregation methods

    // Quantity of sales of a product in the last d days
    public long getQuantity(String product, int d) throws IOException {
        if (d < 1 || d > maxDays) {
            throw new IllegalArgumentException("d out of range");
        }

        int snapshotCurrent;
        lock.lock();
        try {
            snapshotCurrent = currentDay;
        } finally {
            lock.unlock();
        }

        long total = 0;
        int endDay = snapshotCurrent - 1;
        int startDay = snapshotCurrent - d;

        for (int day = startDay; day <= endDay; day++) {
            if (day < 0) continue;
            AggregationCache.Aggregate agg = computeDayAggregateForProduct(day, product);
            total += agg.quantity;
        }

        return total;
    }

    // Volume of sales 
    public double getVolume(String product, int d) throws IOException {
        if (d < 1 || d > maxDays) {
            throw new IllegalArgumentException("d out of range");
        }

        int snapshotCurrent;
        lock.lock();
        try {
            snapshotCurrent = currentDay;
        } finally {
            lock.unlock();
        }

        double total = 0.0;
        int endDay = snapshotCurrent - 1;
        int startDay = snapshotCurrent - d;

        for (int day = startDay; day <= endDay; day++) {
            if (day < 0) continue;
            AggregationCache.Aggregate agg = computeDayAggregateForProduct(day, product);
            total += agg.volume;
        }

        return total;
    }

    // Average Price
    public double getAveragePrice(String product, int d) throws IOException {
        long q = getQuantity(product, d);
        if (q == 0) return 0.0;
        return getVolume(product, d) / q;
    }

    // Max Price
    public double getMaxPrice(String product, int d) throws IOException {
        if (d < 1 || d > maxDays) {
            throw new IllegalArgumentException("d out of range");
        }

        int snapshotCurrent;
        lock.lock();
        try {
            snapshotCurrent = currentDay;
        } finally {
            lock.unlock();
        }

        double max = 0.0;
        int endDay = snapshotCurrent - 1;
        int startDay = snapshotCurrent - d;

        for (int day = startDay; day <= endDay; day++) {
            if (day < 0) continue;
            AggregationCache.Aggregate agg = computeDayAggregateForProduct(day, product);
            if (agg.maxPrice > max) max = agg.maxPrice;
        }

        return max;
    }


    // Wait until two products are sold or the day ends.
    public boolean waitForSimultaneousSales(String p1, String p2) throws InterruptedException {
        lock.lock();
        try {
            int observedDay = currentDay;
            while (currentDay == observedDay &&
                   !(currentDaySoldProducts.contains(p1) && currentDaySoldProducts.contains(p2))) {
                simultaneousCond.await();
            }
            return currentDaySoldProducts.contains(p1) && currentDaySoldProducts.contains(p2);
        } finally {
            lock.unlock();
        }
    }

    // Wait for n consecutive sales of the same product
    public String waitForConsecutiveSales(int n) throws InterruptedException {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be > 0");
        }

        lock.lock();
        try {
            int observedDay = currentDay;
            while (currentDay == observedDay && consecutiveCount < n) {
                consecutiveCond.await();
            }
            if (consecutiveCount >= n) {
                return lastProduct;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }


    private AggregationCache.Aggregate computeDayAggregateForProduct(int day, String product) throws IOException {
        AggregationCache.Aggregate cached = aggregationCache.getAggregate(day, product);
        if (cached != null) {
            return cached;
        }

        List<Event> snapshot = timeSeriesStore.getSnapshotForDay(day);

        AggregationCache.Aggregate agg;
        if (snapshot != null) {
            agg = computeAggregateFromList(product, snapshot);
        } else {
            agg = DaySeries.computeAggregateFromFile(storageDir, day, product);
        }

        aggregationCache.putAggregate(day, product, agg);
        return agg;
    }
    private AggregationCache.Aggregate computeAggregateFromList(String product, List<Event> list) {
        AggregationCache.Aggregate agg = new AggregationCache.Aggregate();
        for (Event e : list) {
            if (e.getProduct().equals(product)) {
                agg.quantity += e.getQuantity();
                agg.volume += e.getQuantity() * e.getPrice();
                if (e.getPrice() > agg.maxPrice) {
                    agg.maxPrice = e.getPrice();
                }
            }
        }
        return agg;
    }


    // Returns events filtered by product set.
    public List<Event> getEventsForProducts(Set<String> productSet, int d) throws IOException {
        if (d < 1 || d > maxDays) {
            throw new IllegalArgumentException("d out of range");
        }

        int snapshotCurrent;
        lock.lock();
        try {
            snapshotCurrent = currentDay;
        } finally {
            lock.unlock();
        }

        int targetDay = snapshotCurrent - d;
        if (targetDay < 0) {
            return List.of();
        }

        List<Event> snapshot = timeSeriesStore.getSnapshotForDay(targetDay);

        List<Event> filtered;
        if (snapshot != null) {
            filtered = snapshot.stream()
                .filter(e -> productSet.contains(e.getProduct()))
                .toList();
        } else {
            filtered = DaySeries.loadAndFilterFromFile(storageDir, targetDay, productSet);
        }

        return filtered;
    }
}
