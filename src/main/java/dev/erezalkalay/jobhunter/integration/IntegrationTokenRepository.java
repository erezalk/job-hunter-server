package dev.erezalkalay.jobhunter.integration;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every lookup except the bearer resolution is scoped to the owner. */
public interface IntegrationTokenRepository extends JpaRepository<IntegrationToken, Long> {

    @Query("select t from IntegrationToken t join fetch t.user where t.tokenHash = :hash")
    Optional<IntegrationToken> findByTokenHashWithUser(@Param("hash") String hash);

    @Query("select t from IntegrationToken t where t.user.id = :userId order by t.createdAt, t.id")
    List<IntegrationToken> findAllForUser(@Param("userId") Long userId);

    /**
     * The owner's token, row-locked for the rest of the transaction, so rename,
     * regenerate and revoke of the same token are serialized (each one sees the
     * outcome of the previous one) and none of them can resurrect a replaced secret.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from IntegrationToken t where t.id = :id and t.user.id = :userId")
    Optional<IntegrationToken> lockByIdForUser(@Param("id") Long id, @Param("userId") Long userId);
}
