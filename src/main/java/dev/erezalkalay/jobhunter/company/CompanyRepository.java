package dev.erezalkalay.jobhunter.company;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Companies are user-owned; every lookup is scoped to the owner (no unscoped finders). */
public interface CompanyRepository extends Repository<Company, Long> {

    Optional<Company> findByUserIdAndNameKey(Long userId, String nameKey);

    boolean existsByUserId(Long userId);

    Company save(Company company);

    /** Removes the user's company once none of their jobs references it any more; no-op otherwise. */
    @Modifying(flushAutomatically = true)
    @Query("delete from Company c where c.id = :companyId and c.user.id = :userId "
            + "and not exists (select j.id from Job j where j.company.id = c.id)")
    int deleteIfUnreferenced(@Param("companyId") Long companyId, @Param("userId") Long userId);
}
