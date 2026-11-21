package dev.erezalkalay.jobhunter.excludedcompany;

import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.common.exception.FieldValidationException;
import dev.erezalkalay.jobhunter.common.exception.ResourceNotFoundException;
import dev.erezalkalay.jobhunter.user.User;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The current user's Excluded Companies: companies AI job discovery should skip.
 * A pure discovery preference, independent of the Board: nothing here reads or
 * writes jobs, companies or applications, so changing the list never affects
 * an existing job and never prevents adding one. Every operation is scoped to
 * the current user: another user's entry id behaves exactly like a missing one.
 */
@Service
@Transactional(readOnly = true)
public class ExcludedCompanyService {

    private final ExcludedCompanyRepository repository;
    private final CurrentUserProvider currentUserProvider;

    public ExcludedCompanyService(ExcludedCompanyRepository repository, CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
    }

    /** The current user's excluded companies in case-insensitive name order. */
    public List<ExcludedCompany> listForCurrentUser() {
        return repository.findAllForUserOrdered(currentUserId());
    }

    /** Just the names, in the same order; what the integration (MCP) endpoint returns. */
    public List<String> listNamesForCurrentUser() {
        return listForCurrentUser().stream().map(ExcludedCompany::getName).toList();
    }

    @Transactional
    public ExcludedCompany addForCurrentUser(String name) {
        String normalized = normalizeName(name);
        User user = currentUserProvider.getCurrentUser();
        repository.findForUserByNameIgnoreCase(user.getId(), normalized).ifPresent(existing -> {
            throw new DuplicateExcludedCompanyException(existing.getName());
        });
        return saveUnique(new ExcludedCompany(user, normalized), normalized);
    }

    /** Renames the entry; changing only the case of its own name is allowed. */
    @Transactional
    public ExcludedCompany renameForCurrentUser(Long id, String name) {
        String normalized = normalizeName(name);
        Long userId = currentUserId();
        ExcludedCompany entry = findOwned(id, userId);
        repository.findForUserByNameIgnoreCase(userId, normalized)
                .filter(other -> !other.getId().equals(entry.getId()))
                .ifPresent(other -> {
                    throw new DuplicateExcludedCompanyException(other.getName());
                });
        entry.rename(normalized);
        return saveUnique(entry, normalized);
    }

    @Transactional
    public void removeForCurrentUser(Long id) {
        repository.delete(findOwned(id, currentUserId()));
        repository.flush();
    }

    /**
     * Trimmed name with runs of whitespace collapsed to one space; empty or too
     * long is a 400 on the {@code name} field.
     */
    static String normalizeName(String name) {
        String normalized = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            throw new FieldValidationException("name", "Company name must not be empty");
        }
        if (normalized.codePointCount(0, normalized.length()) > ExcludedCompany.NAME_MAX_LENGTH) {
            throw new FieldValidationException("name",
                    "Company name must be at most " + ExcludedCompany.NAME_MAX_LENGTH + " characters");
        }
        return normalized;
    }

    // The case-insensitive unique index is the final word: a concurrent add of
    // the same name that passed the check above still ends as a duplicate.
    private ExcludedCompany saveUnique(ExcludedCompany entry, String normalized) {
        try {
            return repository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            if (isUniqueViolation(e)) {
                throw new DuplicateExcludedCompanyException(normalized);
            }
            throw e;
        }
    }

    private static boolean isUniqueViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation
                    && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE) {
                return true;
            }
        }
        return false;
    }

    private ExcludedCompany findOwned(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Excluded company", id));
    }

    private Long currentUserId() {
        return currentUserProvider.getCurrentUser().getId();
    }
}
