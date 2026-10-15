package dev.erezalkalay.jobhunter.cv;

import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.common.exception.InvalidRequestException;
import dev.erezalkalay.jobhunter.common.exception.ResourceNotFoundException;
import dev.erezalkalay.jobhunter.user.User;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly = true)
public class CvService {

    private static final int MAX_FILE_NAME_LENGTH = 255;

    private final CvRepository cvRepository;
    private final CvTextExtractor textExtractor;
    private final CvStorageService storageService;
    private final CurrentUserProvider currentUserProvider;
    private final long maxFileSizeBytes;

    public CvService(
            CvRepository cvRepository,
            CvTextExtractor textExtractor,
            CvStorageService storageService,
            CurrentUserProvider currentUserProvider,
            @Value("${app.cv.max-file-size-bytes}") long maxFileSizeBytes
    ) {
        this.cvRepository = cvRepository;
        this.textExtractor = textExtractor;
        this.storageService = storageService;
        this.currentUserProvider = currentUserProvider;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public Optional<Cv> getActiveCvForCurrentUser() {
        User user = currentUserProvider.getCurrentUser();
        return cvRepository.findByUserIdAndPrimaryTrue(user.getId());
    }

    public record CvFile(byte[] content, String fileName) {
    }

    /**
     * The current user's active CV as the original PDF bytes, for inline
     * preview. Scoped entirely to the caller's own session — there is no CV
     * id parameter anywhere in this path, so there is no cross-user access
     * surface to defend.
     */
    public CvFile getActiveCvFileForCurrentUser() {
        Cv cv = getActiveCvForCurrentUser().orElseThrow(() -> new ResourceNotFoundException("No active CV"));
        byte[] content = storageService.readFile(cv.getStoragePath());
        String fileName = StringUtils.hasText(cv.getFileName()) ? cv.getFileName() : "cv.pdf";
        return new CvFile(content, fileName);
    }

    @Transactional
    public Cv upload(MultipartFile file) {
        validate(file);

        User user = currentUserProvider.getCurrentUser();
        byte[] content = readBytes(file);

        CvTextExtractor.ExtractionResult extraction = textExtractor.extract(content);
        String storagePath = storageService.store(user.getId(), content);

        // Replacing the active CV makes the new upload active; the previous
        // row is kept (not deleted) so match fingerprints referencing it
        // stay resolvable and the app has simple version history for free.
        cvRepository.clearPrimaryForUser(user.getId());

        Cv cv = new Cv(user, StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "CV");
        cv.setFileName(file.getOriginalFilename());
        cv.setContentType(CvTextExtractor.CONTENT_TYPE_PDF);
        cv.setStoragePath(storagePath);
        cv.setFileSizeBytes(file.getSize());
        cv.setExtractedText(extraction.text());
        cv.setExtractionStatus(extraction.status());
        cv.setExtractionWarning(extraction.warning());
        cv.setPrimary(true);
        return cvRepository.save(cv);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("A CV file is required");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new InvalidRequestException(
                    "File is too large — maximum size is " + (maxFileSizeBytes / (1024 * 1024)) + " MB");
        }
        if (!isPdf(file)) {
            throw new InvalidRequestException("Only PDF files are supported");
        }
        // cvs.title / cvs.file_name hold the original name (VARCHAR(255)).
        String name = file.getOriginalFilename();
        if (name != null && name.length() > MAX_FILE_NAME_LENGTH) {
            throw new InvalidRequestException(
                    "File name is too long — use a name of at most " + MAX_FILE_NAME_LENGTH + " characters");
        }
    }

    /**
     * Browsers/clients don't always send a reliable Content-Type for PDF
     * uploads, so fall back to the file extension when the declared type
     * isn't recognized.
     */
    private static boolean isPdf(MultipartFile file) {
        if (CvTextExtractor.CONTENT_TYPE_PDF.equals(file.getContentType())) {
            return true;
        }
        String name = file.getOriginalFilename();
        return StringUtils.hasText(name) && name.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file", e);
        }
    }
}
