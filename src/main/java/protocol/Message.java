package protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Message: requestId, opcode, payload, error flag.
 * Immutable with defensive copies. Supports simple serialization.
 */
public class Message {
    private final int requestId;
    private final Opcode opcode;
    private final byte[] payload;
    private final boolean error;

    public Message(int requestId, Opcode opcode, byte[] payload, boolean error) {
        this.requestId = requestId;
        this.opcode = Objects.requireNonNull(opcode, "opcode");
        this.payload = payload == null ? null : payload.clone();
        this.error = error;
    }

    public static Message success(int requestId, Opcode opcode, byte[] payload) {
        return new Message(requestId, opcode, payload, false);
    }

    public static Message error(int requestId, Opcode opcode, byte[] payload) {
        return new Message(requestId, opcode, payload, true);
    }

    public int getRequestId() { return requestId; }
    public Opcode getOpcode() { return opcode; }
    public byte[] getPayload() { return payload == null ? null : payload.clone(); }
    public boolean isError() { return error; }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(requestId);
        out.writeInt(opcode.getCode());
        out.writeBoolean(error);
        int len = payload == null ? 0 : payload.length;
        out.writeInt(len);
        if (len > 0) out.write(payload);
    }

    public static Message readFrom(DataInputStream in) throws IOException {
        int requestId = in.readInt();
        int op = in.readInt();
        Opcode opcode = Opcode.fromCode(op);
        boolean error = in.readBoolean();
        int len = in.readInt();
        byte[] payload = new byte[len];
        if (len > 0) in.readFully(payload);
        return new Message(requestId, opcode, payload, error);
    }

    @Override
    public String toString() {
        return "Message{" + "requestId=" + requestId + ", opcode=" + opcode +
                ", error=" + error + ", payloadLen=" + (payload == null ? 0 : payload.length) + '}';
    }
}

