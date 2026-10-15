package dev.erezalkalay.jobhunter.support;

import dev.erezalkalay.jobhunter.company.Company;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Test-only full repository for {@link Company}, so tests can provoke real
 * Hibernate constraint failures. Production code only gets the owner-scoped
 * {@code CompanyRepository}.
 */
public interface TestCompanyRepository extends JpaRepository<Company, Long> {
}
