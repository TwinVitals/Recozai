package sn.recozai.identity.application;

public interface Credentials {
    String encode(String password);
    boolean matches(String password, String encoded);
    String token();
    String hash(String token);
}
