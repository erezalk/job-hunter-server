package dev.erezalkalay.jobhunter.jobdata;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Export / import of the current user's job and Board data (format version 1,
 * see {@link JobDataV1}). Browser-session endpoints like every other
 * {@code /api/**} route (session + CSRF); the MCP bearer token cannot reach them.
 */
@RestController
@RequestMapping("/api/job-data")
public class JobDataController {

    private final JobDataExportService exportService;
    private final JobDataImportService importService;
    private final JobDataJson json;
    private final long importMaxBytes;

    public JobDataController(
            JobDataExportService exportService,
            JobDataImportService importService,
            JobDataJson json,
            @Value("${app.job-data.import-max-bytes:52428800}") long importMaxBytes
    ) {
        this.exportService = exportService;
        this.importService = importService;
        this.json = json;
        this.importMaxBytes = importMaxBytes;
    }

    /** Downloads the export as a JSON attachment. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        JobDataV1 data = exportService.exportForCurrentUser();
        String fileName = "job-hunter-export-" + LocalDate.ofInstant(data.exportedAt(), ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body(json.write(data));
    }

    /** Restores an export into the current user's empty Board; all or nothing. */
    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public JobDataImportService.ImportSummary importJobData(InputStream body) {
        return importService.importForCurrentUser(json.read(body, importMaxBytes));
    }
}
