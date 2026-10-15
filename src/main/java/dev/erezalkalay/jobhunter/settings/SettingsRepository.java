package dev.erezalkalay.jobhunter.settings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsRepository extends JpaRepository<Settings, Long> {

    Optional<Settings> findByUserId(Long userId);
}
