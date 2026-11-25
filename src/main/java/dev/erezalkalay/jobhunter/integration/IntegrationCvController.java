package dev.erezalkalay.jobhunter.integration;

import dev.erezalkalay.jobhunter.cv.CvService;
import dev.erezalkalay.jobhunter.cv.CvService.CvFile;
import dev.erezalkalay.jobhunter.cv.CvService.CvText;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only bearer-token access to the token user's active CV, i.e. the same
 * CV the application's CV page uploads, replaces and previews (no second
 * storage). Authenticated by the integration filter chain only; the owner is
 * always the token's user and there are no parameters (no CV id), so there is
 * no cross-user access surface. A replaced CV is served from the next request.
 *
 * <p>{@code GET /cv} returns the metadata and the text extracted by
 * {@link dev.erezalkalay.jobhunter.cv.CvTextExtractor} at upload time;
 * {@code GET /cv/file} returns the original uploaded bytes, unmodified. Both
 * answer 404 with code {@code CV_NOT_FOUND} when no CV has been uploaded.
 */
@RestController
@RequestMapping("/api/integrations/cv")
public class IntegrationCvController {

    /**
     * The active CV for an LLM client. {@code extractionStatus} is SUCCESS,
     * EMPTY (little or no text, e.g. a scanned PDF) or FAILED (the file could
     * not be read as a PDF); for the last two {@code extractionWarning} says
     * why and {@code extractedText} may be empty.
     */
    public record CvTextResponse(
            String fileName,
            String contentType,
            Long fileSizeBytes,
            Instant uploadedAt,
            String extractionStatus,
            String extractionWarning,
            String extractedText) {

        static CvTextResponse of(CvText cv) {
            return new CvTextResponse(cv.fileName(), cv.contentType(), cv.fileSizeBytes(), cv.uploadedAt(),
                    cv.extractionStatus(), cv.extractionWarning(), cv.extractedText());
        }
    }

    private final CvService cvService;

    public IntegrationCvController(CvService cvService) {
        this.cvService = cvService;
    }

    @GetMapping
    public CvTextResponse getCv() {
        return CvTextResponse.of(cvService.getActiveCvTextForCurrentUser());
    }

    @GetMapping("/file")
    public ResponseEntity<byte[]> getCvFile() {
        CvFile file = cvService.getActiveCvFileForCurrentUser();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }
}
