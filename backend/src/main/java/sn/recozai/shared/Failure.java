package sn.recozai.shared;

public final class Failure extends RuntimeException {
    private final int status;
    private final String code;

    public Failure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
