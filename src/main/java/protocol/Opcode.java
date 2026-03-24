package protocol;

public enum Opcode{
    REGISTER(1),
    AUTH(2),
    ADD_EVENT(3),
    NEXT_DAY(4),
    GET_QUANTITY(5),
    GET_VOLUME(6),
    GET_AVERAGE_PRICE(7),
    GET_MAX_PRICE(8),
    WAIT_SIMULTANEOUS(9),
    WAIT_CONSECUTIVE(10),
    GET_EVENTS_FILTER(11),
    LOGOUT(12),
    QUIT(13);

    private final int code;

    Opcode (int code){
        this.code = code;
     }

    public int getCode(){
        return code;
     }

    public static Opcode fromCode(int code) {
        for (Opcode op : Opcode.values()) {
            if (op.getCode() == code) return op;
        }
        throw new IllegalArgumentException("Invalid opcode: " + code);
    }
}