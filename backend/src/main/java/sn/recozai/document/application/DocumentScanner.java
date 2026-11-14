package sn.recozai.document.application;

import sn.recozai.document.api.DocumentModels.ScanStatus;

public interface DocumentScanner {
    ScanOutcome scan(byte[] bytes);

    record ScanOutcome(ScanStatus status, String detail) {}
}
