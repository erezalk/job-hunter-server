package dev.erezalkalay.jobhunter.application;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationStatusRepository extends JpaRepository<ApplicationStatus, String> {

    List<ApplicationStatus> findAllByOrderBySortOrderAsc();
}
