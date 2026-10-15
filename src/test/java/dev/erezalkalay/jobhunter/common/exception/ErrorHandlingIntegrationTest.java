package dev.erezalkalay.jobhunter.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.common.crypto.EncryptionService;
import dev.erezalkalay.jobhunter.company.Company;
import dev.erezalkalay.jobhunter.job.JobService;
import dev.erezalkalay.jobhunter.jobmatch.ai.FakeAiProviderState;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestCompanyRepository;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import dev.erezalkalay.jobhunter.support.TestFiles;
import dev.erezalkalay.jobhunter.user.UserRepository;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Error statuses and bodies: Spring MVC client errors stay 4xx, database
 * constraint failures are classified (unique = 409, bad value = 400, other =
 * sanitized 500), and an unreadable stored API key is explained, not a 500.
 * Uses its own user so the scripted settings never affect other test classes.
 */
class ErrorHandlingIntegrationTest extends IntegrationTestBase {

    private static final String EMAIL = "errors-user@jobhunter.local";
    private static final String PASSWORD = "errors-password-123";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testData;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestCompanyRepository companyRepository;

    @Autowired
    private GlobalExceptionHandler handler;

    @Autowired
    private FakeAiProviderState fakeAiProviderState;

    private TestApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        testData.createUserIfMissing(EMAIL, PASSWORD);
        client = new TestApiClient(port);
        assertThat(client.login(EMAIL, PASSWORD).statusCode()).isEqualTo(200);
        fakeAiProviderState.reset();
    }

    // --- Spring MVC client errors keep their 4xx status ---

    @Test
    void unknownRouteIsNotFound() throws Exception {
        HttpResponse<String> response = client.get("/api/does-not-exist");

        assertClientError(response, 404);
    }

    @Test
    void unconvertiblePathVariableIsABadRequest() throws Exception {
        HttpResponse<String> response = client.get("/api/applications/not-a-number");

        assertClientError(response, 400);
    }

    @Test
    void missingRequiredParameterIsABadRequest() throws Exception {
        HttpResponse<String> response = client.deleteRequest("/api/settings/ai/api-key");

        assertClientError(response, 400);
        assertThat(JsonPath.<String>read(response.body(), "$.message")).contains("provider");
    }

    @Test
    void missingMultipartPartIsABadRequest() throws Exception {
        HttpResponse<String> response = client.postMultipartFile(
                "/api/cv", "notTheFilePart", "cv.pdf", "application/pdf", TestFiles.pdfWithText("irrelevant"));

        assertClientError(response, 400);
        assertThat(JsonPath.<String>read(response.body(), "$.message")).contains("file");
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        HttpResponse<String> response = client.send("POST", "/api/board/jobs", "text/plain", "{\"title\":\"Plain Text\"}");

        assertClientError(response, 415);
    }

    @Test
    void unsupportedMethodIs405WithAllowHeader() throws Exception {
        HttpResponse<String> response = client.send("PUT", "/api/board", "application/json", "{}");

        assertClientError(response, 405);
        assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(allow -> assertThat(allow).contains("GET"));
    }

    // --- Conflicts vs. invalid values ---

    @Test
    void editingAJobIntoAnotherOfMyJobsIsStillAConflict() throws Exception {
        String run = String.valueOf(System.nanoTime());
        client.postJson("/api/board/jobs", "{\"title\":\"Duplicate Role " + run + "\",\"companyName\":\"Errors Co\"}");
        HttpResponse<String> second = client.postJson("/api/board/jobs", "{\"title\":\"Other Role " + run + "\",\"companyName\":\"Errors Co\"}");
        long secondJob = ((Number) JsonPath.read(second.body(), "$.jobId")).longValue();

        HttpResponse<String> duplicate = client.patchJson("/api/jobs/" + secondJob,
                "{\"title\":\"duplicate  role " + run + "\",\"companyName\":\"ERRORS CO\"}");

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(duplicate.body(), "$.message"))
                .isEqualTo("Another job with this company and title already exists.");
    }

    @Test
    void overlongSettingsValueIsAFieldErrorNotAConflict() throws Exception {
        List<String> visible = JsonPath.read(client.get("/api/settings").body(), "$.visibleBoardStatuses");
        String body = "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":" + toJson(visible)
                + ",\"minimumFitScore\":70,\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"" + "m".repeat(101) + "\"}";

        HttpResponse<String> response = client.putJson("/api/settings", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field")).containsExactly("aiModel");
    }

    @Test
    void overlongCvFileNameIsABadRequestNotAConflict() throws Exception {
        HttpResponse<String> response = client.postMultipartFile(
                "/api/cv", "file", "c".repeat(300) + ".pdf", "application/pdf", TestFiles.pdfWithText("Some CV text"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.message")).contains("File name is too long");
    }

    @Test
    void databaseUniqueViolationIsAConflict() {
        String name = "Unique Errors Co " + System.nanoTime();
        companyRepository.saveAndFlush(company(name));
        DataIntegrityViolationException ex = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> companyRepository.saveAndFlush(company(name)));

        ResponseEntity<ApiError> response = handler.handleDataIntegrity(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).isEqualTo("The request conflicts with existing data");
    }

    @Test
    void valueTooLongForItsColumnIsABadRequestNotAConflict() {
        DataIntegrityViolationException ex = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> companyRepository.saveAndFlush(company("x".repeat(300))));

        ResponseEntity<ApiError> response = handler.handleDataIntegrity(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).doesNotContain("companies").doesNotContain("varying");
    }

    @Test
    void otherConstraintFailuresAreASanitizedServerError() {
        DataIntegrityViolationException ex = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> testData.update("INSERT INTO companies (name) VALUES (NULL)"));

        ResponseEntity<ApiError> response = handler.handleDataIntegrity(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    }

    // --- Stored API key that can no longer be decrypted ---

    @Test
    void anUnreadableStoredApiKeyIsExplainedAndLeftUntouched() throws Exception {
        List<String> visible = JsonPath.read(client.get("/api/settings").body(), "$.visibleBoardStatuses");
        assertThat(client.putJson("/api/settings", "{\"theme\":\"SYSTEM\",\"timezone\":\"UTC\",\"visibleBoardStatuses\":"
                + toJson(visible) + ",\"minimumFitScore\":70,\"aiProvider\":\"ANTHROPIC\",\"aiModel\":\"test-model\"}")
                .statusCode()).isEqualTo(200);
        assertThat(client.putJson("/api/settings/ai/api-key",
                "{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + FakeAiProviderState.VALID_API_KEY + "\"}").statusCode())
                .isEqualTo(200);
        assertThat(client.postMultipartFile("/api/cv", "file", "cv.pdf", "application/pdf",
                TestFiles.pdfWithText("Errors User - Senior Java Engineer with Spring Boot experience.")).statusCode())
                .isEqualTo(201);
        HttpResponse<String> added = client.postJson("/api/board/jobs",
                "{\"title\":\"Unreadable Key Role " + System.nanoTime() + "\",\"companyName\":\"Errors Co\","
                        + "\"description\":\"Java and Spring Boot backend work.\"}");
        long jobId = ((Number) JsonPath.read(added.body(), "$.jobId")).longValue();

        // As if APP_ENCRYPTION_SECRET had changed since the key was saved.
        long userId = userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().getId();
        String foreignCiphertext = new EncryptionService("a-different-master-secret").encrypt("some-key");
        testData.update("UPDATE settings SET anthropic_api_key_encrypted = ? WHERE user_id = ?", foreignCiphertext, userId);

        for (HttpResponse<String> response : List.of(
                client.postJson("/api/jobs/" + jobId + "/match/evaluate", "{}"),
                client.postJson("/api/settings/ai/test-connection", "{}"))) {
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(JsonPath.<String>read(response.body(), "$.message"))
                    .contains("can no longer be read").contains("Enter the API key again");
            assertThat(response.body()).doesNotContain(foreignCiphertext).doesNotContain("AEAD").doesNotContain("Exception");
        }
        assertThat(fakeAiProviderState.callCount()).isZero();
        assertThat(testData.count("SELECT count(*) FROM settings WHERE user_id = ? AND anthropic_api_key_encrypted = ?",
                userId, foreignCiphertext)).isEqualTo(1);
    }

    private static void assertClientError(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat((Integer) JsonPath.read(response.body(), "$.status")).isEqualTo(status);
        assertThat(JsonPath.<String>read(response.body(), "$.message")).isNotBlank();
        assertThat(response.body()).doesNotContain("Exception").doesNotContain("at org.");
    }

    private Company company(String name) {
        return new Company(userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow(), name, JobService.normalizeKeyPart(name));
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/test");
    }

    private static String toJson(List<String> values) {
        return "[" + String.join(",", values.stream().map(v -> "\"" + v + "\"").toList()) + "]";
    }
}
