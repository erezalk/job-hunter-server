package dev.erezalkalay.jobhunter.jobmatch.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Shared, test-controllable state behind {@link FakeAnthropicProvider} and
 * {@link FakeGeminiProvider}. Lets tests assert how many times the "AI" was
 * actually called (crucial for match-reuse tests) and script a canned result
 * or failure without ever making a real network call.
 */
@Component
@Profile("test")
public class FakeAiProviderState {

    public static final String VALID_API_KEY = "test-valid-api-key";

    private final AiResponseParser parser;
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile AiMatchResult nextResult = defaultResult();
    private volatile RuntimeException nextException;
    private volatile String nextRawResponse;
    private volatile Runnable duringNextCall;
    private volatile Boolean transactionActiveDuringLastCall;

    public FakeAiProviderState(AiResponseParser parser) {
        this.parser = parser;
    }

    public int callCount() {
        return callCount.get();
    }

    public void reset() {
        callCount.set(0);
        nextResult = defaultResult();
        nextException = null;
        nextRawResponse = null;
        duringNextCall = null;
        transactionActiveDuringLastCall = null;
    }

    public void setNextResult(AiMatchResult result) {
        this.nextResult = result;
    }

    public void setNextException(RuntimeException exception) {
        this.nextException = exception;
    }

    /** The next evaluation returns this raw provider text, run through the real {@link AiResponseParser}. */
    public void setNextRawResponse(String rawResponse) {
        this.nextRawResponse = rawResponse;
    }

    /** Runs while the next evaluation is "waiting for the provider", e.g. to change data concurrently. */
    public void setDuringNextCall(Runnable action) {
        this.duringNextCall = action;
    }

    /** Whether a Spring-managed transaction was active while the last evaluation call ran (null: no call yet). */
    public Boolean transactionActiveDuringLastCall() {
        return transactionActiveDuringLastCall;
    }

    AiMatchResult evaluate() {
        callCount.incrementAndGet();
        transactionActiveDuringLastCall = TransactionSynchronizationManager.isActualTransactionActive();
        Runnable action = duringNextCall;
        if (action != null) {
            duringNextCall = null;
            action.run();
        }
        failIfScripted();
        String raw = nextRawResponse;
        if (raw != null) {
            nextRawResponse = null;
            return parser.parse(raw);
        }
        return nextResult;
    }

    void failIfScripted() {
        RuntimeException exception = nextException;
        if (exception != null) {
            nextException = null;
            throw exception;
        }
    }

    private static AiMatchResult defaultResult() {
        return new AiMatchResult(80,
                List.of("Java", "Spring Boot"), List.of("Kubernetes"), List.of());
    }
}
