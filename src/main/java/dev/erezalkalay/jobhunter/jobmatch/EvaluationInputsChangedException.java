package dev.erezalkalay.jobhunter.jobmatch;

/**
 * The CV, AI provider/model or the job's AI-relevant content changed while
 * the provider was evaluating, so its result no longer describes the current
 * inputs and was not stored.
 */
public class EvaluationInputsChangedException extends RuntimeException {

    public EvaluationInputsChangedException() {
        super("The job, CV or AI settings changed while the evaluation was running, so its result was not saved. "
                + "Evaluate again.");
    }
}
