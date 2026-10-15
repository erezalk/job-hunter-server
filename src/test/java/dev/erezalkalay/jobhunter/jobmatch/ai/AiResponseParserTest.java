package dev.erezalkalay.jobhunter.jobmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class AiResponseParserTest {

    private final AiResponseParser parser = new AiResponseParser(JsonMapper.builder().build());

    @Test
    void acceptsTheWholeZeroToOneHundredRange() {
        assertThat(parser.parse("{\"fitScore\":0}").fitScore()).isZero();
        assertThat(parser.parse("{\"fitScore\":100}").fitScore()).isEqualTo(100);
        assertThat(parser.parse("{\"fitScore\":73,\"matchingSkills\":[\"Java\"]}").fitScore()).isEqualTo(73);
    }

    // A malformed score must never become a real one (0 would trigger LOW_MATCH and discard the description).
    @ParameterizedTest(name = "fitScore = {0}")
    @ValueSource(strings = {"null", "\"85\"", "\"high\"", "true", "72.5", "85.0", "-1", "-20", "101", "150",
            "99999999999", "[80]", "{\"value\":80}"})
    void rejectsAnyFitScoreThatIsNotAnIntegerFromZeroToOneHundred(String fitScore) {
        assertInvalidResponse("{\"fitScore\":" + fitScore + ",\"matchingSkills\":[],\"missingSkills\":[],\"concerns\":[]}");
    }

    @Test
    void defaultsNullListsToEmpty() {
        AiMatchResult result = parser.parse("{\"fitScore\":80}");
        assertThat(result.matchingSkills()).isEmpty();
        assertThat(result.missingSkills()).isEmpty();
        assertThat(result.concerns()).isEmpty();
    }

    @Test
    void ignoresAStraySummaryFieldFromTheProvider() {
        AiMatchResult result = parser.parse(
                "{\"fitScore\":70,\"summary\":\"ignored\",\"matchingSkills\":[\"Java\"],\"missingSkills\":[\"Go\"],\"concerns\":[\"Onsite\"]}");
        assertThat(result.fitScore()).isEqualTo(70);
        assertThat(result.matchingSkills()).containsExactly("Java");
        assertThat(result.missingSkills()).containsExactly("Go");
        assertThat(result.concerns()).containsExactly("Onsite");
    }

    @Test
    void extractsJsonObjectFromSurroundingProse() {
        AiMatchResult result = parser.parse(
                "Here is the result:\n```json\n{\"fitScore\":65,\"matchingSkills\":[\"Java\"],"
                        + "\"missingSkills\":[],\"concerns\":[]}\n```");
        assertThat(result.fitScore()).isEqualTo(65);
        assertThat(result.matchingSkills()).containsExactly("Java");
    }

    @Test
    void throwsMalformedResponseWhenFitScoreIsMissing() {
        assertInvalidResponse("{\"matchingSkills\":[\"Java\"]}");
    }

    @Test
    void throwsMalformedResponseWhenNotParseableJson() {
        assertInvalidResponse("not json at all");
    }

    private void assertInvalidResponse(String raw) {
        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(AiProviderException.class)
                .satisfies(e -> assertThat(((AiProviderException) e).getCode())
                        .isEqualTo(AiErrorCode.AI_INVALID_RESPONSE));
    }
}
