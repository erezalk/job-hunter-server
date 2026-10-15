package dev.erezalkalay.jobhunter.job;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Jobs are user-owned, so every lookup is scoped to the owner. Deliberately a
 * plain {@link Repository} exposing only these methods: there is no
 * {@code findById}/{@code findAll} that could load another user's job.
 */
public interface JobRepository extends Repository<Job, Long> {

    Optional<Job> findByIdAndUserId(Long id, Long userId);

    Optional<Job> findByUserIdAndSourceAndExternalId(Long userId, String source, String externalId);

    Optional<Job> findByUserIdAndCompanyKeyAndTitleKey(Long userId, String companyKey, String titleKey);

    boolean existsByUserId(Long userId);

    Job save(Job job);

    Job saveAndFlush(Job job);

    void delete(Job job);

    void flush();
}
