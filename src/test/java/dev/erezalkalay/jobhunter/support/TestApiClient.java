package dev.erezalkalay.jobhunter.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal cookie-aware HTTP client for exercising the real session + CSRF
 * cookie login flow end to end in integration tests, without pulling a
 * Jackson dependency into the test code.
 */
public class TestApiClient {

    private final String baseUrl;
    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .build();

    public TestApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-XSRF-TOKEN", currentCsrfToken())
                .GET()
                .build();
        return httpClient.send(request, BodyHandlers.ofString());
    }

    public HttpResponse<byte[]> getBytes(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-XSRF-TOKEN", currentCsrfToken())
                .GET()
                .build();
        return httpClient.send(request, BodyHandlers.ofByteArray());
    }

    public HttpResponse<String> postJson(String path, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", currentCsrfToken())
                .POST(BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, BodyHandlers.ofString());
    }

    public HttpResponse<String> deleteRequest(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-XSRF-TOKEN", currentCsrfToken())
                .DELETE()
                .build();
        return httpClient.send(request, BodyHandlers.ofString());
    }

    public HttpResponse<String> patchJson(String path, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", currentCsrfToken())
                .method("PATCH", BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, BodyHandlers.ofString());
    }

    public HttpResponse<String> putJson(String path, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", currentCsrfToken())
                .PUT(BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, BodyHandlers.ofString());
    }

    /** Any method / Content-Type, for exercising Spring MVC's own client errors. */
    public HttpResponse<String> send(String method, String path, String contentType, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-XSRF-TOKEN", currentCsrfToken())
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return httpClient.send(request.build(), BodyHandlers.ofString());
    }

    public HttpResponse<String> login(String email, String password) throws IOException, InterruptedException {
        get("/api/auth/csrf");
        String form = "email=" + encode(email) + "&password=" + encode(password);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", currentCsrfToken())
                .POST(BodyPublishers.ofString(form))
                .build();
        return httpClient.send(request, BodyHandlers.ofString());
    }

    /** Sign-up the way the SPA does it: prime the CSRF cookie, then post JSON. */
    public HttpResponse<String> register(String email, String password, String confirmPassword)
            throws IOException, InterruptedException {
        get("/api/auth/csrf");
        return postJson("/api/auth/register", "{\"email\":" + jsonString(email) + ",\"password\":" + jsonString(password)
                + ",\"confirmPassword\":" + jsonString(confirmPassword) + "}");
    }

    public HttpResponse<String> changePassword(String currentPassword, String newPassword, String confirmNewPassword)
            throws IOException, InterruptedException {
        return postJson("/api/auth/change-password", "{\"currentPassword\":" + jsonString(currentPassword)
                + ",\"newPassword\":" + jsonString(newPassword) + ",\"confirmNewPassword\":" + jsonString(confirmNewPassword) + "}");
    }

    /** POST JSON with this client's cookies but WITHOUT the X-XSRF-TOKEN header (a cross-site request). */
    public HttpResponse<String> postJsonWithoutCsrfHeader(String path, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, BodyHandlers.ofString());
    }

    /** Current value of a cookie this client holds, or null. */
    public String cookie(String name) {
        return cookieManager.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(java.net.HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    /** JSON string literal (null stays null); passwords in tests may contain quotes or backslashes. */
    public static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    public HttpResponse<String> postMultipartFile(
            String path, String fieldName, String fileName, String contentType, byte[] content
    ) throws IOException, InterruptedException {
        String boundary = "----TestBoundary" + UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, fieldName, fileName, contentType, content);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-XSRF-TOKEN", currentCsrfToken())
                .POST(BodyPublishers.ofByteArray(body))
                .build();
        return httpClient.send(request, BodyHandlers.ofString());
    }

    private static byte[] buildMultipartBody(
            String boundary, String fieldName, String fileName, String contentType, byte[] content
    ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private String currentCsrfToken() {
        return cookieManager.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(java.net.HttpCookie::getValue)
                .findFirst()
                .orElse("");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
