package sn.recozai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sn.recozai.identity.application.CustodianService;
import sn.recozai.identity.application.CustodianService.Grant;
import sn.recozai.shared.Failure;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Non-web inbound adapter for signed custodian grants: verifies the Ed25519 signature over the exact
 * payload bytes, parses the grant and delegates all business rules to Identity's {@link CustodianService}.
 * It holds no persistence access and never uses clinic RBAC.
 */
@Service
public class IdentityOperator {
    private final String publicKey;
    private final ObjectMapper json;
    private final CustodianService custodian;
    public IdentityOperator(@Value("${app.custodian-public-key}") String publicKey, ObjectMapper json, CustodianService custodian) {
        this.publicKey=publicKey; this.json=json; this.custodian=custodian;
    }
    public String issue(String envelope) {
        return custodian.issue(verify(envelope));
    }
    private Grant verify(String envelope) {
        if (publicKey.isBlank()) throw new Failure(403,"CUSTODIAN_NOT_CONFIGURED");
        try {
            if (envelope.length()>16000) throw new Failure(403,"INVALID_OPERATOR_GRANT");
            String[] parts=envelope.strip().split("\\.");
            if (parts.length!=2) throw new Failure(403,"INVALID_OPERATOR_GRANT");
            byte[] payload=Base64.getUrlDecoder().decode(parts[0]);
            PublicKey key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            Signature verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(key); verifier.update(payload);
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[1]))) throw new Failure(403,"INVALID_OPERATOR_GRANT");
            return json.readValue(new String(payload,StandardCharsets.UTF_8),Grant.class);
        } catch (GeneralSecurityException | IllegalArgumentException | tools.jackson.core.JacksonException error) {
            throw new Failure(403,"INVALID_OPERATOR_GRANT");
        }
    }
}
