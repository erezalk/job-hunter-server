package dev.erezalkalay.jobhunter.searchprompt;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every lookup is scoped to the owner. */
public interface SearchPromptRepository extends JpaRepository<SearchPrompt, Long> {

    Optional<SearchPrompt> findByUserId(Long userId);

    @Modifying
    @Query("delete from SearchPrompt p where p.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
