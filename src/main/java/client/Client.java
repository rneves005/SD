package client;

import model.Event;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Client facade for UI code. Delegates to ClientLibrary and hides protocol details.
 */
public class Client implements Closeable {
    private final ClientLibrary lib;

    public Client(String host, int port) throws IOException {
        this.lib = new ClientLibrary(host, port);
    }

    public void register(String user, String pass) throws IOException { lib.register(user, pass); }
    public void authenticate(String user, String pass) throws IOException { lib.authenticate(user, pass); }
    public void logout() throws IOException { lib.logout(); }
    public void addEvent(String product, int quantity, double price) throws IOException { lib.addEvent(product, quantity, price); }
    public void nextDay() throws IOException { lib.nextDay(); }
    public long getQuantity(String product, int d) throws IOException { return lib.getQuantity(product, d); }
    public double getVolume(String product, int d) throws IOException { return lib.getVolume(product, d); }
    public double getAveragePrice(String product, int d) throws IOException { return lib.getAveragePrice(product, d); }
    public double getMaxPrice(String product, int d) throws IOException { return lib.getMaxPrice(product, d); }
    public boolean waitForSimultaneous(String p1, String p2) throws IOException { return lib.waitForSimultaneous(p1, p2); }
    public String waitForConsecutive(int n) throws IOException { return lib.waitForConsecutive(n); }
    public List<Event> getEventsForProducts(Set<String> productSet, int day) throws IOException { return lib.getEventsForProducts(productSet, day); }

    @Override
    public void close() throws IOException { lib.close(); }

    /**
     * Register a simple textual listener for unsolicited messages. The helper keeps
     * the implementation compact and easy to read.
     */
    public void setUnsolicitedListener(Consumer<String> consumer) {
        lib.setUnsolicitedListener(m -> consumer.accept(formatUnsolicited(m)));
    }

    private static String formatUnsolicited(protocol.Message m) {
        byte[] p = m.getPayload();
        String s = new String(p == null ? new byte[0] : p, java.nio.charset.StandardCharsets.UTF_8);
        return "tag=" + m.getRequestId() + " opcode=" + m.getOpcode() + " error=" + m.isError() + " payload=" + s;
    }
}
