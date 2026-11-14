package sn.recozai.identity.adapter;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import sn.recozai.identity.application.Credentials;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
public class PasswordCredentials implements Credentials {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    @Override public String encode(String password) { return encoder.encode(password); }
    @Override public boolean matches(String password, String encoded) {
        return password.getBytes(StandardCharsets.UTF_8).length <= 72 && encoder.matches(password, encoded);
    }
    @Override public String token() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    @Override public String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
}
