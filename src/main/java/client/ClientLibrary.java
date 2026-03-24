package client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import model.Event;
import protocol.EventListSerializer;

public class ClientLibrary implements Closeable {

    private final Socket socket;
    private final protocol.TaggedConnection conn;
    private final protocol.Demultiplexer demux;
    private volatile boolean closed = false;

    public ClientLibrary(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.conn = new protocol.TaggedConnection(socket);
        this.demux = new protocol.Demultiplexer(conn);
    }

    // --- Core request/response helpers ---
    private interface RespParser<T> {
        T parse(DataInputStream in) throws IOException;
    }





    private <T> RequestHandle<T> sendRequest(protocol.Opcode opcode, RequestWriter writer, RespParser<T> parser) throws IOException {
        if (closed) throw new IOException("client closed");
        byte[] payload = null;
        if (writer != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            writer.write(dos);
            dos.flush();
            payload = baos.toByteArray();
        }
        protocol.Demultiplexer.RequestHandle dh = demux.sendAndRegister(opcode, payload);
        return new RequestHandle<>(dh, parser);
    }

    private <T> T sendSync(protocol.Opcode opcode, RequestWriter writer, RespParser<T> parser) throws IOException {
        RequestHandle<T> h = sendRequest(opcode, writer, parser);
        try {
            return h.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    @FunctionalInterface
    private interface RequestWriter {
        void write(DataOutputStream out) throws IOException;
    }



    // --- API methods ---
    public void register(String user, String pass) throws IOException {
        sendSync(protocol.Opcode.REGISTER, out -> { out.writeUTF(user); out.writeUTF(pass); }, inStream -> null);
    }

    public void authenticate(String user, String pass) throws IOException {
        sendSync(protocol.Opcode.AUTH, out -> { out.writeUTF(user); out.writeUTF(pass); }, inStream -> null);
    }

    public void logout() throws IOException {
        sendSync(protocol.Opcode.LOGOUT, null, inStream -> null);
    }

    public void addEvent(String product, int quantity, double price) throws IOException {
        sendSync(protocol.Opcode.ADD_EVENT, out -> { out.writeUTF(product); out.writeInt(quantity); out.writeDouble(price); }, inStream -> null);
    }

    public void nextDay() throws IOException {
        sendSync(protocol.Opcode.NEXT_DAY, null, inStream -> null);
    }

    /**
     * Register an unsolicited listener. The listener receives full {@link protocol.Message} objects.
     * This is a low-level hook kept for demos and server pushes; typical UI code may prefer the
     * higher-level facade in `Client.setUnsolicitedListener`.
     */
    public void setUnsolicitedListener(protocol.Demultiplexer.UnsolicitedListener l) {
        demux.setUnsolicitedListener(l);
    }

    public long getQuantity(String product, int d) throws IOException {
        return sendSync(protocol.Opcode.GET_QUANTITY, out -> { out.writeUTF(product); out.writeInt(d); }, inStream -> inStream.readLong());
    }

    public double getVolume(String product, int d) throws IOException {
        return sendSync(protocol.Opcode.GET_VOLUME, out -> { out.writeUTF(product); out.writeInt(d); }, inStream -> inStream.readDouble());
    }

    public double getAveragePrice(String product, int d) throws IOException {
        long q = getQuantity(product, d);
        if (q == 0) return 0.0;
        double vol = getVolume(product, d);
        return vol / q;
    }

    public double getMaxPrice(String product, int d) throws IOException {
        return sendSync(protocol.Opcode.GET_MAX_PRICE, out -> { out.writeUTF(product); out.writeInt(d); }, inStream -> inStream.readDouble());
    }

    public boolean waitForSimultaneous(String p1, String p2) throws IOException {
        RequestHandle<Boolean> h = sendRequest(protocol.Opcode.WAIT_SIMULTANEOUS, out -> { out.writeUTF(p1); out.writeUTF(p2); }, inStream -> inStream.readBoolean());
        try {
            return h.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    public RequestHandle<Boolean> waitForSimultaneousAsync(String p1, String p2) throws IOException {
        return sendRequest(protocol.Opcode.WAIT_SIMULTANEOUS, out -> { out.writeUTF(p1); out.writeUTF(p2); }, inStream -> inStream.readBoolean());
    }

    public String waitForConsecutive(int n) throws IOException {
        RequestHandle<String> h = sendRequest(protocol.Opcode.WAIT_CONSECUTIVE, out -> out.writeInt(n), inStream -> inStream.readUTF());
        try {
            String res = h.get();
            return res == null || res.isEmpty() ? null : res;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    public RequestHandle<String> waitForConsecutiveAsync(int n) throws IOException {
        return sendRequest(protocol.Opcode.WAIT_CONSECUTIVE, out -> out.writeInt(n), inStream -> {
            String res = inStream.readUTF();
            return res == null || res.isEmpty() ? null : res;
        });
    }

    // --- Event Filtering ---

    /**
     * Get events filtered by a set of products from day d (d days ago, excluding current day).
     * Uses efficient serialization to minimize bandwidth.
     */
    public List<Event> getEventsForProducts(Set<String> productSet, int day) throws IOException {
        return sendSync(protocol.Opcode.GET_EVENTS_FILTER, out -> {
            out.writeInt(productSet.size());
            for (String product : productSet) {
                out.writeUTF(product);
            }
            out.writeInt(day);
        }, inStream -> {
            return EventListSerializer.deserialize(inStream);
        });
    }

    // --- Close ---
    @Override
    public void close() throws IOException {
        if (closed) return;
        try {
            // send QUIT and then close socket
            try {
                sendSync(protocol.Opcode.QUIT, null, inStream -> null); // QUIT
            } catch (IOException ignored) {
                // ignore errors during shutdown
            }
        } finally {
            closed = true;
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // Optional helper to check connection
    public boolean isClosed() { return closed || socket.isClosed(); }

    // RequestHandle: wrapper around Demultiplexer.RequestHandle applying a parser
    public static class RequestHandle<T> {
        private final protocol.Demultiplexer.RequestHandle dh;
        private final RespParser<T> parser;

        RequestHandle(protocol.Demultiplexer.RequestHandle dh, RespParser<T> parser) { this.dh = dh; this.parser = parser; }

        /**
         * Waits for and returns the parsed response.
         * <p>
         * If the connection fails before a response is received, the underlying
         * {@code Demultiplexer.RequestHandle.get()} returns {@code null} and this method
         * throws an {@link IOException} with message "connection closed" to provide a
         * clear error to callers (Option B behaviour).
         */
        public T get() throws IOException, InterruptedException {
            protocol.Message m = dh.get();
            if (m == null) throw new IOException("connection closed");
            if (m.isError()) {
                byte[] p = m.getPayload();
                String msg = "";
                if (p != null && p.length > 0) {
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(p));
                    msg = in.readUTF();
                }
                throw new IOException(msg);
            }
            byte[] p = m.getPayload();
            if (parser == null) return null;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(p == null ? new byte[0] : p));
            return parser.parse(in);
        }

        public boolean isDone() { return dh.isDone(); }
    }
}
