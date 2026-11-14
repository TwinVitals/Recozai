/**
 * Shared access kernel: the authenticated principal, fixed roles and the narrow {@link sn.recozai.access.Access}
 * port. Every module may depend on it; it depends on no module (only {@code shared}). Identity implements the port.
 * Keeping these types here (rather than in Identity) prevents module cycles such as Identity ↔ Audit.
 */
package sn.recozai.access;
