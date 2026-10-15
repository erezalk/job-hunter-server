package dev.erezalkalay.jobhunter.application;

import dev.erezalkalay.jobhunter.application.dto.ApplicationNotesRequest;
import dev.erezalkalay.jobhunter.application.dto.ApplicationResponse;
import dev.erezalkalay.jobhunter.application.dto.ApplicationStageRequest;
import dev.erezalkalay.jobhunter.application.dto.ApplicationStageResponse;
import dev.erezalkalay.jobhunter.application.dto.ApplicationStatusHistoryResponse;
import dev.erezalkalay.jobhunter.application.dto.ApplicationStatusResponse;
import dev.erezalkalay.jobhunter.application.dto.MoveRequest;
import dev.erezalkalay.jobhunter.application.dto.StarRequest;
import dev.erezalkalay.jobhunter.application.dto.StatusChangeRequest;
import dev.erezalkalay.jobhunter.application.dto.UpcomingStageResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping("/applications")
    public List<ApplicationResponse> listApplications(@RequestParam(required = false) String status) {
        return applicationService.listApplicationsForCurrentUser(status).stream()
                .map(ApplicationResponse::from)
                .toList();
    }

    @GetMapping("/applications/{id}")
    public ApplicationResponse getApplication(@PathVariable Long id) {
        return ApplicationResponse.from(applicationService.getApplication(id));
    }

    @PatchMapping("/applications/{id}/status")
    public ApplicationResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest request) {
        return ApplicationResponse.from(applicationService.changeStatus(id, request.statusCode(), request.notes()));
    }

    @PatchMapping("/applications/{id}/move")
    public ApplicationResponse move(@PathVariable Long id, @Valid @RequestBody MoveRequest request) {
        return ApplicationResponse.from(applicationService.moveApplication(id, request));
    }

    @PatchMapping("/applications/{id}/starred")
    public ApplicationResponse updateStarred(@PathVariable Long id, @Valid @RequestBody StarRequest request) {
        return ApplicationResponse.from(applicationService.updateStarred(id, request.starred()));
    }

    @PatchMapping("/applications/{id}/notes")
    public ApplicationResponse updateNotes(@PathVariable Long id, @Valid @RequestBody ApplicationNotesRequest request) {
        return ApplicationResponse.from(applicationService.updateNotes(id, request.notes()));
    }

    @DeleteMapping("/applications/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApplication(@PathVariable Long id) {
        applicationService.deleteApplication(id);
    }

    @GetMapping("/applications/{id}/status-history")
    public List<ApplicationStatusHistoryResponse> getStatusHistory(@PathVariable Long id) {
        return applicationService.getStatusHistory(id).stream()
                .map(ApplicationStatusHistoryResponse::from)
                .toList();
    }

    @GetMapping("/applications/{id}/stages")
    public List<ApplicationStageResponse> getStages(@PathVariable Long id) {
        return applicationService.getStages(id).stream()
                .map(ApplicationStageResponse::from)
                .toList();
    }

    @GetMapping("/stages/upcoming")
    public List<UpcomingStageResponse> getUpcomingStages() {
        return applicationService.getUpcomingStages();
    }

    @PostMapping("/applications/{id}/stages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationStageResponse addStage(@PathVariable Long id, @Valid @RequestBody ApplicationStageRequest request) {
        return ApplicationStageResponse.from(applicationService.addStage(id, request));
    }

    @PatchMapping("/applications/{id}/stages/{stageId}")
    public ApplicationStageResponse updateStage(
            @PathVariable Long id, @PathVariable Long stageId, @Valid @RequestBody ApplicationStageRequest request) {
        return ApplicationStageResponse.from(applicationService.updateStage(id, stageId, request));
    }

    @DeleteMapping("/applications/{id}/stages/{stageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStage(@PathVariable Long id, @PathVariable Long stageId) {
        applicationService.deleteStage(id, stageId);
    }

    @GetMapping("/application-statuses")
    public List<ApplicationStatusResponse> listStatuses() {
        return applicationService.listStatuses().stream()
                .map(ApplicationStatusResponse::from)
                .toList();
    }
}
