package dev.erezalkalay.jobhunter.jobmatch.ai;

/**
 * The AI providers supported for job matching. Deliberately just an enum of
 * *providers* (there are only two, and adding a third is a small, deliberate
 * change) — never confuse this with model names, which must stay a free-text
 * string per provider so new models don't require a deployment.
 */
public enum ProviderId {
    ANTHROPIC,
    GEMINI;

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        for (ProviderId id : values()) {
            if (id.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
