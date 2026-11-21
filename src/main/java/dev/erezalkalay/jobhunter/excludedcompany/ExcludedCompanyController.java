package dev.erezalkalay.jobhunter.excludedcompany;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings → Job Discovery → Excluded Companies, for the normal logged-in
 * browser session (session + CSRF) only; a bearer token cannot reach it, so
 * the list can only be edited in the application UI. MCP clients read it via
 * {@code GET /api/integrations/excluded-companies}.
 */
@RestController
@RequestMapping("/api/settings/excluded-companies")
public class ExcludedCompanyController {

    /** Body of add and rename. The name is trimmed; empty is a 400. */
    public record ExcludedCompanyRequest(String name) {
    }

    public record ExcludedCompanyResponse(Long id, String name) {
        static ExcludedCompanyResponse of(ExcludedCompany entry) {
            return new ExcludedCompanyResponse(entry.getId(), entry.getName());
        }
    }

    private final ExcludedCompanyService service;

    public ExcludedCompanyController(ExcludedCompanyService service) {
        this.service = service;
    }

    @GetMapping
    public List<ExcludedCompanyResponse> list() {
        return service.listForCurrentUser().stream().map(ExcludedCompanyResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExcludedCompanyResponse add(@RequestBody ExcludedCompanyRequest request) {
        return ExcludedCompanyResponse.of(service.addForCurrentUser(request.name()));
    }

    @PatchMapping("/{id}")
    public ExcludedCompanyResponse rename(@PathVariable Long id, @RequestBody ExcludedCompanyRequest request) {
        return ExcludedCompanyResponse.of(service.renameForCurrentUser(id, request.name()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        service.removeForCurrentUser(id);
        return ResponseEntity.noContent().build();
    }
}
