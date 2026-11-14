package sn.recozai.document.adapter;

import sn.recozai.document.api.DocumentModels.ScanStatus;
import sn.recozai.document.application.DocumentScanner;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConfigurableDocumentScanner implements DocumentScanner {
    private static final String EICAR_SIGNATURE =
        "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    private static final String SYNTHETIC_MALWARE_MARKER = "RECOZAI-MALICIOUS-TEST-PAYLOAD";

    private final AtomicBoolean available = new AtomicBoolean(true);

    public void setAvailable(boolean isAvailable) {
        this.available.set(isAvailable);
    }

    public boolean isAvailable() {
        return this.available.get();
    }

    @Override
    public ScanOutcome scan(byte[] bytes) {
        if (!available.get()) {
            return new ScanOutcome(ScanStatus.SCANNER_UNAVAILABLE, "SCANNER_OUTAGE_FAIL_CLOSED");
        }
        String ascii = new String(bytes, StandardCharsets.ISO_8859_1);
        if (ascii.contains(EICAR_SIGNATURE) || ascii.contains(SYNTHETIC_MALWARE_MARKER)) {
            return new ScanOutcome(ScanStatus.MALICIOUS_DETECTED, "SIGNATURE_DETECTED");
        }
        return new ScanOutcome(ScanStatus.CLEAN, "CLEAN_VERIFIED");
    }
}
