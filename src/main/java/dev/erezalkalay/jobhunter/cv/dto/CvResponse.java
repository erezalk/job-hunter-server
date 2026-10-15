package dev.erezalkalay.jobhunter.cv.dto;

import dev.erezalkalay.jobhunter.cv.Cv;
import java.time.Instant;

public record CvResponse(
        Long id,
        String fileName,
        String contentType,
        Long fileSizeBytes,
        String extractionStatus,
        String extractionWarning,
        String extractedText,
        Instant uploadedAt
) {

    public static CvResponse from(Cv cv) {
        return new CvResponse(
                cv.getId(),
                cv.getFileName(),
                cv.getContentType(),
                cv.getFileSizeBytes(),
                cv.getExtractionStatus(),
                cv.getExtractionWarning(),
                cv.getExtractedText(),
                cv.getCreatedAt()
        );
    }
}
