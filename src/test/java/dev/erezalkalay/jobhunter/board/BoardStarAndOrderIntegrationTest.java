package dev.erezalkalay.jobhunter.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.erezalkalay.jobhunter.IntegrationTestBase;
import dev.erezalkalay.jobhunter.support.TestApiClient;
import dev.erezalkalay.jobhunter.support.TestDataSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class BoardStarAndOrderIntegrationTest extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataSupport testDataSupport;

    private TestApiClient client;
    private String company;
    private final List<Long> mine = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        client = new TestApiClient(port);
        assertThat(client.login("test@jobhunter.local", "test-password-123").statusCode()).isEqualTo(200);
        company = "Order Co " + System.nanoTime();
        mine.clear();
    }

    @Test
    void starDefaultsToFalseAndOnlyTheStarFlagChanges() throws Exception {
        long a = track("Starred Role");
        client.patchJson("/api/applications/" + a + "/status", "{\"statusCode\":\"CV_SENT\"}");
        client.patchJson("/api/applications/" + a + "/notes", "{\"notes\":\"keep me\"}");
        assertThat(card(a, "starred")).isEqualTo(false);

        HttpResponse<String> starred = client.patchJson("/api/applications/" + a + "/starred", "{\"starred\":true}");

        assertThat(starred.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(starred.body(), "$.starred")).isTrue();
        assertThat(card(a, "starred")).isEqualTo(true);
        HttpResponse<String> application = client.get("/api/applications/" + a);
        assertThat(JsonPath.<String>read(application.body(), "$.status.code")).isEqualTo("CV_SENT");
        assertThat(JsonPath.<String>read(application.body(), "$.notes")).isEqualTo("keep me");
        assertThat(JsonPath.<List<Object>>read(client.get("/api/applications/" + a + "/status-history").body(), "$[*]"))
                .hasSize(2);

        assertThat(client.patchJson("/api/applications/" + a + "/starred", "{\"starred\":false}").statusCode())
                .isEqualTo(200);
        assertThat(card(a, "starred")).isEqualTo(false);
        assertThat(client.patchJson("/api/applications/" + a + "/starred", "{}").statusCode()).isEqualTo(400);
    }

    @Test
    void anotherUserCanNeitherStarNorMoveMyTicket() throws Exception {
        long a = track("Private Ticket");
        testDataSupport.createUserIfMissing("board-other@jobhunter.local", "other-password-123");
        TestApiClient other = new TestApiClient(port);
        assertThat(other.login("board-other@jobhunter.local", "other-password-123").statusCode()).isEqualTo(200);

        assertThat(other.patchJson("/api/applications/" + a + "/starred", "{\"starred\":true}").statusCode())
                .isEqualTo(404);
        assertThat(other.patchJson("/api/applications/" + a + "/move", "{\"statusCode\":\"OFFER\"}").statusCode())
                .isEqualTo(404);

        assertThat(card(a, "starred")).isEqualTo(false);
        assertThat(card(a, "statusCode")).isEqualTo("NEW");
    }

    @Test
    void newTicketsLandOnTopOfTheColumn() throws Exception {
        long a = track("First");
        long b = track("Second");
        long c = track("Third");

        assertThat(order()).containsExactly(c, b, a);
    }

    @Test
    void sameColumnReorderPersistsWithoutStatusChangeOrHistory() throws Exception {
        long a = track("A");
        long b = track("B");
        long c = track("C");
        assertThat(order()).containsExactly(c, b, a);

        assertThat(move(a, "NEW", "beforeApplicationId", c).statusCode()).isEqualTo(200);
        assertThat(order()).containsExactly(a, c, b);

        move(a, "NEW", "afterApplicationId", b);
        assertThat(order()).containsExactly(c, b, a);

        move(c, "NEW", null, null);
        assertThat(order()).containsExactly(b, a, c);

        move(a, "NEW", "afterApplicationId", b);
        assertThat(order()).containsExactly(b, a, c);

        for (long id : mine) {
            assertThat(card(id, "statusCode")).isEqualTo("NEW");
            assertThat(JsonPath.<List<Object>>read(client.get("/api/applications/" + id + "/status-history").body(), "$[*]"))
                    .hasSize(1);
        }
    }

    @Test
    void crossColumnMoveChangesStatusWithHistoryAndHonoursTheDropPosition() throws Exception {
        long a = track("A");
        long b = track("B");
        long c = track("C");
        long mover = track("Mover");
        move(a, "CV_SENT", null, null);
        move(b, "CV_SENT", null, null);
        move(c, "CV_SENT", null, null);
        assertThat(order()).containsExactly(mover);
        List<Long> cvSent = columnIds("CV_SENT");
        assertThat(cvSent).containsSubsequence(a, b, c);

        HttpResponse<String> response = move(mover, "CV_SENT", "afterApplicationId", a);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.status.code")).isEqualTo("CV_SENT");
        assertThat(columnIds("CV_SENT")).containsSubsequence(a, mover, b, c);
        assertThat(JsonPath.<List<String>>read(client.get("/api/applications/" + mover + "/status-history").body(),
                "$[*].status.code")).containsExactly("NEW", "CV_SENT");
        // Positions inside the destination column are unique, so rendering is deterministic.
        assertThat(testDataSupport.count("select count(*) - count(distinct board_position) from applications "
                + "where status_code = 'CV_SENT' and id in (" + mine.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + ")")).isZero();
    }

    @Test
    void statusChangeWithoutADropPositionLandsOnTop() throws Exception {
        long a = track("A");
        long b = track("B");
        move(a, "CV_SENT", null, null);
        client.patchJson("/api/applications/" + b + "/status", "{\"statusCode\":\"CV_SENT\"}");

        assertThat(columnIds("CV_SENT")).containsSubsequence(b, a);
    }

    @Test
    void invalidMovesAreRejectedAndChangeNothing() throws Exception {
        long a = track("A");
        long b = track("B");
        move(b, "CV_SENT", null, null);

        assertThat(move(a, "NEW", "beforeApplicationId", b).statusCode()).isEqualTo(400);
        assertThat(move(a, "NEW", "beforeApplicationId", a).statusCode()).isEqualTo(400);
        assertThat(move(a, "NEW", "beforeApplicationId", 999999L).statusCode()).isEqualTo(400);
        assertThat(client.patchJson("/api/applications/" + a + "/move",
                "{\"statusCode\":\"NEW\",\"beforeApplicationId\":" + b + ",\"afterApplicationId\":" + b + "}")
                .statusCode()).isEqualTo(400);
        assertThat(client.patchJson("/api/applications/" + a + "/move", "{\"statusCode\":\"NOPE\"}").statusCode())
                .isEqualTo(409);
        assertThat(card(a, "statusCode")).isEqualTo("NEW");
        assertThat(card(b, "statusCode")).isEqualTo("CV_SENT");
    }

    private long track(String title) throws Exception {
        HttpResponse<String> response = client.postJson("/api/board/jobs",
                "{\"title\":\"" + title + "\",\"companyName\":\"" + company + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        long id = ((Number) JsonPath.read(response.body(), "$.id")).longValue();
        mine.add(id);
        return id;
    }

    private HttpResponse<String> move(long id, String status, String anchorField, Long anchorId) throws Exception {
        String anchor = anchorField != null ? ",\"" + anchorField + "\":" + anchorId : "";
        return client.patchJson("/api/applications/" + id + "/move", "{\"statusCode\":\"" + status + "\"" + anchor + "}");
    }

    // My NEW tickets in the order the Board returns them.
    private List<Long> order() throws Exception {
        return columnIds("NEW");
    }

    private List<Long> columnIds(String status) throws Exception {
        List<Number> ids = JsonPath.read(client.get("/api/board").body(),
                "$[?(@.statusCode=='" + status + "')].applicationId");
        return ids.stream().map(Number::longValue).filter(mine::contains).toList();
    }

    private Object card(long applicationId, String field) throws Exception {
        List<Object> values = JsonPath.read(client.get("/api/board").body(),
                "$[?(@.applicationId==" + applicationId + ")]." + field);
        return values.get(0);
    }
}
