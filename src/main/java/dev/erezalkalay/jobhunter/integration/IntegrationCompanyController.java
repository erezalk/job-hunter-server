package dev.erezalkalay.jobhunter.integration;

import dev.erezalkalay.jobhunter.company.CompanyService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only bearer-token view of the companies the token's user already has
 * (so an external AI can skip them during job discovery). Authenticated by the
 * integration filter chain only; the owner is always the token's user, there
 * are no parameters, and only company names are returned (no ids).
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationCompanyController {

    public record CompanyItem(String name) {
    }

    public record CompaniesResponse(List<CompanyItem> companies) {
    }

    private final CompanyService companyService;

    public IntegrationCompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping("/companies")
    public CompaniesResponse getCompanies() {
        return new CompaniesResponse(companyService.listNamesForCurrentUser().stream().map(CompanyItem::new).toList());
    }
}
