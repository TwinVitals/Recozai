package sn.recozai.document.adapter;

import sn.recozai.document.application.DocumentStorage;
import sn.recozai.shared.Failure;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.regex.Pattern;

public final class LocalDocumentStorage implements DocumentStorage {
    private static final Pattern SAFE_KEY = Pattern.compile("^(quarantine|clean)/[0-9a-f\\-]{36}\\.bin$");

    private final Path rootDir;

    public LocalDocumentStorage(Path rootDir) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDir.resolve("quarantine"));
            Files.createDirectories(this.rootDir.resolve("clean"));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot initialize private document storage", error);
        }
    }

    @Override
    public String writeQuarantine(UUID documentId, byte[] bytes) {
        String key = "quarantine/" + UUID.randomUUID() + ".bin";
        Path target = resolveSafe(key);
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return key;
        } catch (IOException error) {
            throw new Failure(500, "DOCUMENT_STORAGE_FAILURE");
        }
    }

    @Override
    public String promoteToClean(String quarantineKey, UUID documentId) {
        Path source = resolveSafe(quarantineKey);
        String cleanKey = "clean/" + UUID.randomUUID() + ".bin";
        Path target = resolveSafe(cleanKey);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return cleanKey;
        } catch (IOException moveError) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return cleanKey;
            } catch (IOException fallbackError) {
                throw new Failure(500, "DOCUMENT_STORAGE_FAILURE");
            }
        }
    }

    @Override
    public byte[] read(String storageKey) {
        Path source = resolveSafe(storageKey);
        try {
            if (!Files.exists(source)) {
                throw new Failure(404, "DOCUMENT_CONTENT_MISSING");
            }
            return Files.readAllBytes(source);
        } catch (IOException error) {
            throw new Failure(500, "DOCUMENT_STORAGE_FAILURE");
        }
    }

    @Override
    public void deleteQuietly(String storageKey) {
        if (storageKey == null) return;
        try {
            Path target = resolveSafe(storageKey);
            Files.deleteIfExists(target);
        } catch (Exception ignored) {
            // Best-effort cleanup of quarantined object
        }
    }

    private Path resolveSafe(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            throw new Failure(422, "INVALID_STORAGE_KEY");
        }
        Path resolved = rootDir.resolve(key).normalize();
        if (!resolved.startsWith(rootDir)) {
            throw new Failure(422, "INVALID_STORAGE_KEY");
        }
        return resolved;
    }
}
