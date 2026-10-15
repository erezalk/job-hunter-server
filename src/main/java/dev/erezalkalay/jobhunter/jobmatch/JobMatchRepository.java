package dev.erezalkalay.jobhunter.jobmatch;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * AI matches are owned by the job's user; every lookup and delete is scoped to
 * that user. A plain {@link Repository} on purpose: no unscoped finders.
 */
public interface JobMatchRepository extends Repository<JobMatch, Long> {

    Optional<JobMatch> findByJobIdAndUserId(Long jobId, Long userId);

    List<JobMatch> findAllByUserIdAndJobIdIn(Long userId, List<Long> jobIds);

    List<JobMatch> findAllByUserId(Long userId);

    boolean existsByUserId(Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from JobMatch m where m.job.id = :jobId and m.user.id = :userId")
    int deleteByJobIdAndUserId(@Param("jobId") Long jobId, @Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from JobMatch m where m.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    JobMatch save(JobMatch match);
}
