package sn.recozai.document.application;

import java.util.UUID;

public interface DocumentStorage {
    String writeQuarantine(UUID documentId, byte[] bytes);
    String promoteToClean(String quarantineKey, UUID documentId);
    byte[] read(String storageKey);
    void deleteQuietly(String storageKey);
}
