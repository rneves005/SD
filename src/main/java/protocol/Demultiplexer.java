package protocol;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 *Demultiplexer - allows multiple threads to make requests in parallel.
* A reader thread delivers the responses to the correct requests.
 */
public class Demultiplexer {
    private final TaggedConnection conn;
    private final Map<Integer, Pending> pending = new HashMap<>();

    // 3 separate locks to avoid contention
    private final ReentrantLock pendingLock = new ReentrantLock();
    private final ReentrantLock stateLock = new ReentrantLock();
    private final ReentrantLock idLock = new ReentrantLock();

    private final Thread reader;
    private volatile boolean closed = false;

    public interface UnsolicitedListener {
        void onMessage(Message m);
    }

    private UnsolicitedListener unsolicitedListener = null;

    private static class Pending {
        Message data;
        boolean done = false;
        final ReentrantLock lock = new ReentrantLock();
        final Condition doneCond = lock.newCondition();
    }

    public static class RequestHandle {
        private final int tag;
        private final Pending pending;
        RequestHandle(int tag, Pending pending) { this.tag = tag; this.pending = pending; }

        // Bloqueia até receber resposta
        public Message get() throws InterruptedException {
            pending.lock.lock();
            try {
                while (!pending.done) pending.doneCond.await();
                return pending.data;
            } finally {
                pending.lock.unlock();
            }
        }

        public boolean isDone() {
            pending.lock.lock();
            try { return pending.done; } finally { pending.lock.unlock(); }
        }

        public int getTag() { return tag; }
    }

    private int nextId = 1;

    public Demultiplexer(TaggedConnection conn) {
        this.conn = conn;
        this.reader = new Thread(this::readerLoop, "demux-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public void setUnsolicitedListener(UnsolicitedListener l) {
        stateLock.lock();
        try { this.unsolicitedListener = l; } finally { stateLock.unlock(); }
    }

    private RequestHandle register(int tag) {
        Pending p = new Pending();
        pendingLock.lock();
        try {
            if (pending.containsKey(tag)) throw new IllegalStateException("tag already registered: " + tag);
            pending.put(tag, p);
        } finally { pendingLock.unlock(); }
        return new RequestHandle(tag, p);
    }

    public RequestHandle sendAndRegister(Opcode opcode, byte[] payload) throws IOException {
        int id = nextTag();
        RequestHandle h = register(id);
        Message m = Message.success(id, opcode, payload);
        conn.send(m);
        return h;
    }

    public void send(Message m) throws IOException {
        conn.send(m);
    }

    private int nextTag() {
        idLock.lock();
        try { return nextId++; } finally { idLock.unlock(); }
    }

    private void readerLoop() {
        try {
            while (!closed) {
                Message m;
                try {
                    m = conn.receive();
                } catch (IOException e) {
                    failAll();
                    break;
                }
                // deliver
                Pending p;
                pendingLock.lock();
                try { p = pending.remove(m.getRequestId()); } finally { pendingLock.unlock(); }

                if (p != null) {
                    p.lock.lock();
                    try {
                        p.data = m;
                        p.done = true;
                        p.doneCond.signalAll();
                    } finally { p.lock.unlock(); }
                } else {
                    // unsolicited
                    UnsolicitedListener l;
                    stateLock.lock();
                    try { l = unsolicitedListener; } finally { stateLock.unlock(); }
                    if (l != null) {
                        try { l.onMessage(m); } catch (Throwable ignored) {}
                    }
                    // else ignore
                }
            }
        } finally {
            closeSilently();
        }
    }

    private void failAll() {
        pendingLock.lock();
        try {
            for (Pending p : pending.values()) {
                p.lock.lock();
                try {
                    p.done = true;
                    // indicate failure by leaving p.data == null
                    p.data = null;
                    p.doneCond.signalAll();
                } finally { p.lock.unlock(); }
            }
            pending.clear();
        } finally { pendingLock.unlock(); }
    }

    public void close() {
        closed = true;
        try { conn.close(); } catch (IOException ignored) {}
        reader.interrupt();
        failAll();
    }

    private void closeSilently() {
        try { conn.close(); } catch (IOException ignored) {}
    }
}
