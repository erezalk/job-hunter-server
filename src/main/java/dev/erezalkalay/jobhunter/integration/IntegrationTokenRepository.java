package dev.erezalkalay.jobhunter.integration;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationTokenRepository extends JpaRepository<IntegrationToken, Long> {

    @Query("select t from IntegrationToken t join fetch t.user where t.tokenHash = :hash")
    Optional<IntegrationToken> findByTokenHashWithUser(@Param("hash") String hash);

    Optional<IntegrationToken> findByUserId(Long userId);

    @Modifying
    @Query("delete from IntegrationToken t where t.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
