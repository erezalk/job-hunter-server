package dev.erezalkalay.jobhunter.integration;

import dev.erezalkalay.jobhunter.company.CompanyPositions;
import dev.erezalkalay.jobhunter.company.CompanyService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only bearer-token view of the companies the token's user already has on
 * their board, each with the titles of all its jobs there (so an external AI can
 * skip companies and positions it would otherwise rediscover). Authenticated by
 * the integration filter chain only; the owner is always the token's user, there
 * are no parameters, and only company names and job titles are returned (no ids,
 * URLs, statuses or scores).
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationCompanyController {

    private final CompanyService companyService;

    public IntegrationCompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping("/companies")
    public List<CompanyPositions> getCompanies() {
        return companyService.listPositionsForCurrentUser();
    }
}
