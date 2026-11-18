package dev.erezalkalay.jobhunter.company;

import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final CurrentUserProvider currentUserProvider;

    public CompanyService(CompanyRepository companyRepository, CurrentUserProvider currentUserProvider) {
        this.companyRepository = companyRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * The current user's companies, each once, with the titles of all their jobs there (no title twice per company),
     * whatever the status of those jobs' tickets. A company exists exactly while one of the user's jobs references
     * it, so every company listed has at least one position. Companies and titles are in case-insensitive order.
     */
    public List<CompanyPositions> listPositionsForCurrentUser() {
        Long userId = currentUserProvider.getCurrentUser().getId();
        Map<String, Set<String>> titlesByCompany = new LinkedHashMap<>();
        for (Object[] row : companyRepository.findCompanyNameAndJobTitleByUserIdOrdered(userId)) {
            titlesByCompany.computeIfAbsent((String) row[0], company -> new LinkedHashSet<>()).add((String) row[1]);
        }
        return titlesByCompany.entrySet().stream()
                .map(entry -> new CompanyPositions(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }
}
