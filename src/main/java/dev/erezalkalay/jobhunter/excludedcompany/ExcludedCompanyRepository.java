package dev.erezalkalay.jobhunter.excludedcompany;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Excluded companies are user-owned; every lookup is scoped to the owner (no unscoped finders). */
public interface ExcludedCompanyRepository extends Repository<ExcludedCompany, Long> {

    /** The user's entries in case-insensitive name order, ties broken by the exact text. */
    @Query("select e from ExcludedCompany e where e.user.id = :userId order by lower(e.name), e.name, e.id")
    List<ExcludedCompany> findAllForUserOrdered(@Param("userId") Long userId);

    Optional<ExcludedCompany> findByIdAndUserId(Long id, Long userId);

    /** The user's entry with this name ignoring case; the same lower() as the unique index. */
    @Query("select e from ExcludedCompany e where e.user.id = :userId and lower(e.name) = lower(:name)")
    Optional<ExcludedCompany> findForUserByNameIgnoreCase(@Param("userId") Long userId, @Param("name") String name);

    ExcludedCompany saveAndFlush(ExcludedCompany excludedCompany);

    void delete(ExcludedCompany excludedCompany);

    void flush();
}
