package protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TaggedConnection that sends and receives `Message` objects using the
 * Message.writeTo/readFrom wire format.
 */
public class TaggedConnection implements AutoCloseable {
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ReentrantLock outLock = new ReentrantLock();

    public TaggedConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    public void send(Message m) throws IOException {
        outLock.lock();
        try {
            m.writeTo(out);
            out.flush();
        } finally {
            outLock.unlock();
        }
    }

    public Message receive() throws IOException {
        return Message.readFrom(in);
    }

    @Override
    public void close() throws IOException {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
