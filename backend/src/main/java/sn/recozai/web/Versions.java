package sn.recozai.web;

import sn.recozai.shared.Failure;

public final class Versions {
    private Versions() {}
    public static long parse(String header) {
        if (header == null) throw new Failure(428, "PRECONDITION_REQUIRED");
        if (!header.matches("\"[0-9]{1,18}\"")) throw new Failure(400, "INVALID_PRECONDITION");
        return Long.parseLong(header.substring(1, header.length() - 1));
    }
}
