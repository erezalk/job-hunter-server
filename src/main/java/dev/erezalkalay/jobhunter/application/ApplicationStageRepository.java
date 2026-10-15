package dev.erezalkalay.jobhunter.application;

import dev.erezalkalay.jobhunter.application.dto.UpcomingStageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Stages are owned through their Application. Single-stage lookups are scoped
 * to the application AND its owner; list queries by application id are only
 * called after the application itself was resolved for the current user.
 */
public interface ApplicationStageRepository extends Repository<ApplicationStage, Long> {

    Optional<ApplicationStage> findByIdAndApplication_IdAndApplication_User_Id(Long id, Long applicationId, Long userId);

    void deleteAllByApplicationId(Long applicationId);

    List<ApplicationStage> findAllByApplication_User_Id(Long userId);

    // Scheduled stages first (ordered by their date), then unscheduled
    // stages at the end in the order they were added.
    @Query("SELECT s FROM ApplicationStage s WHERE s.application.id = :applicationId "
            + "ORDER BY CASE WHEN s.scheduledAt IS NULL THEN 1 ELSE 0 END, s.scheduledAt ASC, s.createdAt ASC")
    List<ApplicationStage> findAllByApplicationIdOrderedForDisplay(@Param("applicationId") Long applicationId);

    // One joined query (no N+1) for every future scheduled stage of the user's
    // own applications, whatever the application's status. Ties on the
    // timestamp fall back to the stage id so the order is deterministic.
    @Query("SELECT new dev.erezalkalay.jobhunter.application.dto.UpcomingStageResponse("
            + "s.id, s.name, s.scheduledAt, a.id, j.id, j.title, c.name) "
            + "FROM ApplicationStage s JOIN s.application a JOIN a.job j LEFT JOIN j.company c "
            + "WHERE a.user.id = :userId AND s.scheduledAt > :now "
            + "ORDER BY s.scheduledAt ASC, s.id ASC")
    List<UpcomingStageResponse> findUpcomingForUser(@Param("userId") Long userId, @Param("now") Instant now);

    ApplicationStage save(ApplicationStage stage);

    void delete(ApplicationStage stage);
}
