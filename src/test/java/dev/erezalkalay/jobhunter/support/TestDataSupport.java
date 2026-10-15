package dev.erezalkalay.jobhunter.support;

import dev.erezalkalay.jobhunter.cv.CvRepository;
import dev.erezalkalay.jobhunter.job.JobRepository;
import dev.erezalkalay.jobhunter.jobmatch.JobMatch;
import dev.erezalkalay.jobhunter.jobmatch.JobMatchRepository;
import dev.erezalkalay.jobhunter.user.User;
import dev.erezalkalay.jobhunter.user.UserRepository;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only helper for directly resetting persistence state that isn't
 * reachable through the public API (e.g. there is no "unset the active CV"
 * endpoint) — needed because integration tests share one Testcontainers
 * Postgres instance across the whole run (see {@code IntegrationTestBase}),
 * so each test must be able to reset preconditions itself.
 */
@Component
public class TestDataSupport {

    private final CvRepository cvRepository;
    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final JobMatchRepository jobMatchRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public TestDataSupport(
            CvRepository cvRepository,
            UserRepository userRepository,
            JobRepository jobRepository,
            JobMatchRepository jobMatchRepository,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate
    ) {
        this.cvRepository = cvRepository;
        this.userRepository = userRepository;
        this.jobRepository = jobRepository;
        this.jobMatchRepository = jobMatchRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void clearActiveCv(Long userId) {
        cvRepository.clearPrimaryForUser(userId);
    }

    @Transactional
    public void createUserIfMissing(String email, String password) {
        if (!userRepository.existsByEmailIgnoreCase(email)) {
            userRepository.save(new User(email, passwordEncoder.encode(password), "Other User"));
        }
    }

    public long userId(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    /** A stored match for the job, owned (like every match) by the job's owner. */
    @Transactional
    public void createJobMatch(Long jobId, int score) {
        Long ownerId = jdbcTemplate.queryForObject("SELECT user_id FROM jobs WHERE id = ?", Long.class, jobId);
        JobMatch match = new JobMatch(jobRepository.findByIdAndUserId(jobId, ownerId).orElseThrow());
        match.setMatchScore(BigDecimal.valueOf(score));
        jobMatchRepository.save(match);
    }

    public int count(String sql, Object... args) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    public java.util.List<String> strings(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, String.class, args);
    }

    public void update(String sql, Object... args) {
        jdbcTemplate.update(sql, args);
    }

    public long insertRawJob(long userId, String title, String companyKey, String titleKey) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO jobs (user_id, title, company_key, title_key, discovered_at) VALUES (?, ?, ?, ?, now()) RETURNING id",
                Long.class, userId, title, companyKey, titleKey);
    }
}
