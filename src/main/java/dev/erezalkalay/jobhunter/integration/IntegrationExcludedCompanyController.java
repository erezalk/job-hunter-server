package dev.erezalkalay.jobhunter.integration;

import dev.erezalkalay.jobhunter.excludedcompany.ExcludedCompanyService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only bearer-token view of the token user's Excluded Companies (companies
 * an external AI should skip during job discovery), as a plain array of names.
 * Authenticated by the integration filter chain only; the owner is always the
 * token's user and there are no parameters. There is deliberately no write
 * endpoint here: the list is edited only in the application's Settings.
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationExcludedCompanyController {

    private final ExcludedCompanyService excludedCompanyService;

    public IntegrationExcludedCompanyController(ExcludedCompanyService excludedCompanyService) {
        this.excludedCompanyService = excludedCompanyService;
    }

    @GetMapping("/excluded-companies")
    public List<String> getExcludedCompanies() {
        return excludedCompanyService.listNamesForCurrentUser();
    }
}
