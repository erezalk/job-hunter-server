package dev.erezalkalay.jobhunter.cv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Persists uploaded CV file bytes to disk. Stored filenames are
 * server-generated (UUID), never derived from the user-supplied original
 * filename, so there is no path-traversal or collision risk; the original
 * filename is kept separately in {@link Cv#getFileName()} for display only.
 * Storage paths are never exposed to the frontend.
 */
@Component
public class CvStorageService {

    private final Path storageDir;

    public CvStorageService(@Value("${app.cv.storage-dir}") String storageDir) {
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create CV storage directory: " + this.storageDir, e);
        }
    }

    public String store(Long userId, byte[] content) {
        String fileName = userId + "-" + UUID.randomUUID() + ".pdf";
        Path target = storageDir.resolve(fileName);
        try {
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store CV file", e);
        }
        return target.toString();
    }

    /**
     * Reads back a previously stored CV file. {@code storagePath} always
     * comes from a persisted {@link Cv} row, never directly from client
     * input, but this still refuses to read outside the configured storage
     * directory as defense in depth against a corrupted/forged path.
     */
    public byte[] readFile(String storagePath) {
        Path path = Path.of(storagePath).toAbsolutePath().normalize();
        if (!path.startsWith(storageDir)) {
            throw new UncheckedIOException(
                    new IOException("Refusing to read CV file outside the configured storage directory"));
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read CV file", e);
        }
    }
}
