package server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import model.Event;
import protocol.EventListSerializer;
import protocol.Message;
import protocol.Opcode;
import protocol.TaggedConnection;

/**
* Thread that generates a client.
* Translates protocol messages for calls to ServerState.
* Validates authentication before operations.
*/
public class ClientHandler implements Runnable {

    private final ServerState serverState;
    private final Socket clientSocket;
    private String loggedUser = null;

    public ClientHandler(ServerState serverState, Socket clientSocket) {
        this.serverState = serverState;
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try (TaggedConnection conn = new TaggedConnection(clientSocket)) {
            while (true) {
                Message m;
                try {
                    m = conn.receive();
                } catch (IOException e) {
                    break; // connection closed or broken
                }
                if (m == null) break;

                Opcode opcode = m.getOpcode();
                int requestId = m.getRequestId();

                try {
                    ByteArrayInputStream bais = new ByteArrayInputStream(m.getPayload() == null ? new byte[0] : m.getPayload());
                    DataInputStream in = new DataInputStream(bais);

                    switch (opcode) {
                        case REGISTER -> {
                            String user = in.readUTF();
                            String pass = in.readUTF();
                            if (serverState.userAlreadyExists(user)) {
                                conn.send(Message.error(requestId, opcode, utfBytes("user exists")));
                            } else {
                                serverState.addUser(user, pass);
                                conn.send(Message.success(requestId, opcode, null));
                            }
                        }
                        case AUTH -> {
                            String user = in.readUTF();
                            String pass = in.readUTF();
                            boolean ok = serverState.authenticateUser(user, pass);
                            if (ok) {
                                loggedUser = user;
                                conn.send(Message.success(requestId, opcode, null));
                            } else {
                                conn.send(Message.error(requestId, opcode, utfBytes("auth failed")));
                            }
                        }
                        case ADD_EVENT -> {
                            if (!ensureAuthenticated(conn, opcode, requestId)) break;
                            String product = in.readUTF();
                            int quantity = in.readInt();
                            double price = in.readDouble();
                            try {
                                serverState.addEvent(product, quantity, price);
                                conn.send(Message.success(requestId, opcode, null));
                            } catch (IOException e) {
                                conn.send(Message.error(requestId, opcode, utfBytes("io error: " + e.getMessage())));
                            }
                        }
                        case NEXT_DAY -> {
                            if (!ensureAuthenticated(conn, opcode, requestId)) break;
                            serverState.nextDay();
                            conn.send(Message.success(requestId, opcode, null));
                        }
                        case GET_QUANTITY -> {
                            if (!ensureAuthenticated(conn, opcode, requestId)) break;
                            String product = in.readUTF();
                            int d = in.readInt();
                            try {
                                long qty = serverState.getQuantity(product, d);
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                DataOutputStream dos = new DataOutputStream(baos);
                                dos.writeLong(qty);
                                dos.flush();
                                conn.send(Message.success(requestId, opcode, baos.toByteArray()));
                            } catch (IOException e) {
                                conn.send(Message.error(requestId, opcode, utfBytes("io error: " + e.getMessage())));
                            }
                        }
                        case GET_VOLUME -> {
                            if (!ensureAuthenticated(conn, opcode, requestId)) break;
                            String product = in.readUTF();
                            int d = in.readInt();
                            try {
                                double vol = serverState.getVolume(product, d);
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                DataOutputStream dos = new DataOutputStream(baos);
                                dos.writeDouble(vol);
                                dos.flush();
                                conn.send(Message.success(requestId, opcode, baos.toByteArray()));
                            } catch (IOException e) {
                                conn.send(Message.error(requestId, opcode, utfBytes("io error: " + e.getMessage())));
                            }
                        }
                        case GET_AVERAGE_PRICE -> {
                            if (!ensureAuthenticated(conn, opcode, requestId)) break;
                            String product = in.readUTF();
                            int d = in.readInt();
                            try {
                                double avg = serverState.getAveragePrice(product, d);
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                DataOutputStream dos = new DataOutputStream(baos);
                                dos.writeDouble(avg);
                                dos.flush();
                                conn.send(Message.success(requestId, opcode, baos.toByteArray()));
                            } catch (IOException e) {
                                conn.send(Message.error(requestId, opcode, utfBytes("io error: " + e.getMessage())));
                            }
                        }
                        case GET_MAX_PRICE -> {
                            if (!ensureAuthenticated(conn, opcode, requestId)) break;
                            String product = in.readUTF();
                            int d = in.readInt();
                            try {
                                double max = serverState.getMaxPrice(product, d);
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                DataOutputStream dos = new DataOutputStream(baos);
                                dos.writeDouble(max);
                                dos.flush();
                                conn.send(Message.success(requestId, opcode, baos.toByteArray()));
                            } catch (IOException e) {
                                conn.send(Message.error(requestId, opcode, utfBytes("io error: " + e.getMessage())));
                            }
                        }
                        case WAIT_SIMULTANEOUS -> {
                            if (!ensureAuthenticated(conn, opcode, requestId)) break;
                            String p1 = in.readUTF();
                            String p2 = in.readUTF();
                            try {
                                boolean happened = serverState.waitForSimultaneousSales(p1, p2);
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                DataOutputStream dos = new DataOutputStream(baos);
                                dos.writeBoolean(happened);
                                dos.flush();
                                conn.send(Message.success(requestId, opcode, baos.toByteArray()));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                conn.send(Message.error(requestId, opcode, utfBytes("interrupted")));
                            }
                        }
                        case WAIT_CONSECUTIVE -> {
                            if (!ensureAuthenticated(conn, opcode, requestId)) break;
                            int n = in.readInt();
                            try {
                                String product = serverState.waitForConsecutiveSales(n);
                                conn.send(Message.success(requestId, opcode, utfBytes(product == null ? "" : product)));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                conn.send(Message.error(requestId, opcode, utfBytes("interrupted")));
                            }
                        }
                        case GET_EVENTS_FILTER -> {
                            if (!ensureAuthenticated(conn, opcode, requestId)) break;

                            // Read set of products and day
                            int numProducts = in.readInt();
                            Set<String> productSet = new HashSet<>();
                            for (int i = 0; i < numProducts; i++) {
                                productSet.add(in.readUTF());
                            }
                            int day = in.readInt();

                            // Get filtered events
                            List<Event> events = serverState.getEventsForProducts(productSet, day);

                            // Serialize efficiently
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            DataOutputStream out = new DataOutputStream(baos);
                            EventListSerializer.serialize(events, out);

                            conn.send(Message.success(requestId, opcode, baos.toByteArray()));
                        }
                        case LOGOUT -> {
                            if (loggedUser != null) {
                                serverState.logoutUser(loggedUser);
                                loggedUser = null;
                            }
                            conn.send(Message.success(requestId, opcode, null));
                        }
                        case QUIT -> {
                            conn.send(Message.success(requestId, opcode, null));
                            return; // close connection
                        }
                        default -> {
                            conn.send(Message.error(requestId, opcode, utfBytes("unknown opcode")));
                        }
                    }
                } catch (IOException e) {
                    try {
                        conn.send(Message.error(requestId, opcode, utfBytes("protocol io error: " + e.getMessage())));
                    } catch (IOException ignored) {
                    }
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {}
        }
    }

    private boolean ensureAuthenticated(TaggedConnection conn, Opcode opcode, int requestId) throws IOException {
        if (loggedUser == null) {
            conn.send(Message.error(requestId, opcode, utfBytes("not authenticated")));
            return false;
        }
        return true;
    }

    private static byte[] utfBytes(String s) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(s == null ? "" : s);
        dos.flush();
        return baos.toByteArray();
    }
}