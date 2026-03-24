package model;

public class Event {
    private final String product;
    private final int quantity;
    private final double price;

    public Event(String product, int quantity, double price) {
        this.product = product;
        this.quantity = quantity;
        this.price = price;
    }

    public String getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getPrice() {
        return price;
    }

    /**
     * Serialize this event to CSV format.
     * Format: product,quantity,price
     */
    public String toCsv() {
        // Note: product can contain commas in a real project; here we keep it simple
        return product + "," + quantity + "," + price;
    }

    /**
     * Deserialize an event from CSV format.
     */
    public static Event fromCsv(String csv) {
        String[] parts = csv.split(",");
        String prod = parts[0];
        int q = Integer.parseInt(parts[1]);
        double p = Double.parseDouble(parts[2]);
        return new Event(prod, q, p);
    }

    @Override
    public String toString() {
        return "Event{product='" + product + "', quantity=" + quantity + ", price=" + price + "}";
    }
}
