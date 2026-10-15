package dev.erezalkalay.jobhunter.application;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Applications are user-owned; every lookup is scoped to the owner. A plain
 * {@link Repository} on purpose: no unscoped {@code findById}/{@code findAll}.
 */
public interface ApplicationRepository extends Repository<Application, Long> {

    List<Application> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    List<Application> findAllByUserIdAndStatus_CodeOrderByCreatedAtDesc(Long userId, String statusCode);

    Optional<Application> findByJobIdAndUserId(Long jobId, Long userId);

    Optional<Application> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserId(Long userId);

    List<Application> findAllByUserIdOrderByBoardPositionAscIdDesc(Long userId);

    @Query("select coalesce(min(a.boardPosition), 0) from Application a where a.user.id = :userId and a.status.code = :statusCode")
    int minBoardPosition(@Param("userId") Long userId, @Param("statusCode") String statusCode);

    // Locks the column so concurrent moves for the same user are serialized.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Application a where a.user.id = :userId and a.status.code = :statusCode "
            + "order by a.boardPosition asc, a.id desc")
    List<Application> lockColumn(@Param("userId") Long userId, @Param("statusCode") String statusCode);

    Application save(Application application);

    void delete(Application application);

    void flush();
}
