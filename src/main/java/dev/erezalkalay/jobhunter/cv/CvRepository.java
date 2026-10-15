package dev.erezalkalay.jobhunter.cv;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CvRepository extends JpaRepository<Cv, Long> {

    List<Cv> findAllByUserId(Long userId);

    Optional<Cv> findByUserIdAndPrimaryTrue(Long userId);

    @Modifying
    @Query("update Cv c set c.primary = false where c.user.id = :userId and c.primary = true")
    void clearPrimaryForUser(@Param("userId") Long userId);
}
