package dev.erezalkalay.jobhunter.company;

import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import java.util.List;
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
     * Names of the current user's companies, each once, whatever the status of
     * the tickets of their jobs there (a company exists exactly while one of
     * the user's jobs references it).
     */
    public List<String> listNamesForCurrentUser() {
        return companyRepository.findNamesByUserIdOrdered(currentUserProvider.getCurrentUser().getId());
    }
}
