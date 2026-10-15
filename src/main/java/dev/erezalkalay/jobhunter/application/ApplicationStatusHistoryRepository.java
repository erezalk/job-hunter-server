package dev.erezalkalay.jobhunter.application;

import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * History is owned through its Application; queries by application id are only
 * called after the application itself was resolved for the current user.
 */
public interface ApplicationStatusHistoryRepository extends Repository<ApplicationStatusHistory, Long> {

    List<ApplicationStatusHistory> findAllByApplicationIdOrderByEnteredAtAsc(Long applicationId);

    void deleteAllByApplicationId(Long applicationId);

    List<ApplicationStatusHistory> findAllByApplication_User_Id(Long userId);

    ApplicationStatusHistory save(ApplicationStatusHistory entry);
}
