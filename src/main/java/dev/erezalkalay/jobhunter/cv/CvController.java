package dev.erezalkalay.jobhunter.cv;

import dev.erezalkalay.jobhunter.cv.CvService.CvFile;
import dev.erezalkalay.jobhunter.cv.dto.ActiveCvResponse;
import dev.erezalkalay.jobhunter.cv.dto.CvResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/cv")
public class CvController {

    private final CvService cvService;

    public CvController(CvService cvService) {
        this.cvService = cvService;
    }

    @GetMapping
    public ActiveCvResponse getActiveCv() {
        return cvService.getActiveCvForCurrentUser()
                .map(cv -> new ActiveCvResponse(CvResponse.from(cv)))
                .orElse(ActiveCvResponse.NONE);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CvResponse upload(@RequestParam("file") MultipartFile file) {
        return CvResponse.from(cvService.upload(file));
    }

    /**
     * Serves the current user's active CV as the original PDF, for inline
     * browser preview. Requires the same authenticated session as every
     * other {@code /api/**} endpoint (see SecurityConfig) and is scoped to
     * the caller's own CV only — there is no CV id parameter here, so there
     * is no cross-user access surface.
     */
    @GetMapping("/file")
    public ResponseEntity<byte[]> getActiveCvFile() {
        CvFile file = cvService.getActiveCvFileForCurrentUser();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.fileName()).build().toString())
                .body(file.content());
    }
}
