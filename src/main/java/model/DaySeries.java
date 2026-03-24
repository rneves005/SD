package model;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// One-day series of events
public class DaySeries {
    private final int dayNumber;
    private final List<Event> events;

    public DaySeries(int dayNumber) {
        this.dayNumber = dayNumber;
        this.events = new ArrayList<>();
    }

    private DaySeries(int dayNumber, List<Event> events) {
        this.dayNumber = dayNumber;
        this.events = events;
    }

    public int getDayNumber() {
        return dayNumber;
    }

    public void addEvent(Event event) {
        events.add(event);
    }

    public List<Event> getEventsSnapshot() {
        return new ArrayList<>(events);
    }

    public List<Event> getEvents() {
        return events;
    }

    // Write CSV
    public void appendEventToDisk(Path storageDir, Event event) throws IOException {
        Path filePath = storageDir.resolve("day_" + dayNumber + ".csv");
        try (BufferedWriter w = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(event.toCsv());
            w.newLine();
        }
    }

    /**
     * Save all events to disk (overwrite mode).
     */
    public void saveToDisk(Path storageDir) throws IOException {
        Path filePath = storageDir.resolve("day_" + dayNumber + ".csv");
        try (BufferedWriter w = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Event event : events) {
                w.write(event.toCsv());
                w.newLine();
            }
        }
    }

    // Load series from disk
    public static DaySeries loadFromDisk(Path storageDir, int dayNumber) throws IOException {
        Path filePath = storageDir.resolve("day_" + dayNumber + ".csv");
        List<Event> events = new ArrayList<>();

        if (Files.exists(filePath)) {
            try (BufferedReader r = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    events.add(Event.fromCsv(line));
                }
            }
        }

        return new DaySeries(dayNumber, events);
    }

    // Calculates aggregates for a product
    public AggregationCache.Aggregate computeAggregateForProduct(String product) {
        AggregationCache.Aggregate agg = new AggregationCache.Aggregate();
        for (Event e : events) {
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

   // Reads from disk and calculates aggregates (without loading everything into memory)
    public static AggregationCache.Aggregate computeAggregateFromFile(Path storageDir, int dayNumber, String product) throws IOException {
        AggregationCache.Aggregate agg = new AggregationCache.Aggregate();
        Path filePath = storageDir.resolve("day_" + dayNumber + ".csv");

        if (!Files.exists(filePath)) {
            return agg;
        }

        try (BufferedReader r = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                Event e = Event.fromCsv(line);
                if (e.getProduct().equals(product)) {
                    agg.quantity += e.getQuantity();
                    agg.volume += e.getQuantity() * e.getPrice();
                    if (e.getPrice() > agg.maxPrice) {
                        agg.maxPrice = e.getPrice();
                    }
                }
            }
        }

        return agg;
    }

    // Reads from disk and filters events by product set.
    public static List<Event> loadAndFilterFromFile(Path storageDir, int dayNumber, Set<String> productSet) throws IOException {
        List<Event> filtered = new ArrayList<>();
        Path filePath = storageDir.resolve("day_" + dayNumber + ".csv");

        if (!Files.exists(filePath)) {
            return filtered;
        }

        try (BufferedReader r = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                Event e = Event.fromCsv(line);
                if (productSet.contains(e.getProduct())) {
                    filtered.add(e);
                }
            }
        }

        return filtered;
    }

    @Override
    public String toString() {
        return "DaySeries{day=" + dayNumber + ", events=" + events.size() + "}";
    }
}
