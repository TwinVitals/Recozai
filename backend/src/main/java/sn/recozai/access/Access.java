package sn.recozai.access;

/** Narrow port: re-resolves the supplied principal against current session, membership and epoch state. */
public interface Access {
    Actor current(Actor supplied);
}
