package com.example.license.validator;

import com.example.license.validator.support.ResilienceDecorators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference validator implementation that delegates to an HTTP license backend.
 *
 * <p>Rule {@code config} keys:
 * <ul>
 *   <li>{@code endpoint} — required; URL of the license backend</li>
 *   <li>{@code method} — HTTP method: GET or POST (default: POST)</li>
 *   <li>{@code tokenHeader} — header forwarded to backend (default: x-license-token)</li>
 *   <li>{@code successStatus} — list of HTTP status codes treated as allow (default: [200])</li>
 *   <li>{@code denyStatus} — list of HTTP status codes treated as explicit deny (default: [401,402,403])</li>
 * </ul>
 */
@Component
public class RemoteHttpValidator implements LicenseValidator {

    private static final Logger log = LoggerFactory.getLogger(RemoteHttpValidator.class);

    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, ResilienceDecorators> resilienceByRuleId = new ConcurrentHashMap<>();

    public RemoteHttpValidator() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofMillis(200))
                .executor(java.util.concurrent.Executors.newFixedThreadPool(16,
                        r -> new Thread(r, "remote-validator-io")))
                .build();
    }

    // Package-private for testing
    RemoteHttpValidator(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "remote-http";
    }

    @Override
    public CompletableFuture<ValidationResult> validate(ValidationRequest request) {
        Map<String, Object> cfg = request.rule().config();
        String endpoint = configStr(cfg, "endpoint", null);
        if (endpoint == null || endpoint.isBlank()) {
            log.error("Rule '{}': remote-http validator requires 'endpoint' in config", request.rule().id());
            return CompletableFuture.completedFuture(ValidationResult.denyDefault());
        }

        String httpMethod = configStr(cfg, "method", "POST").toUpperCase();
        String tokenHeader = configStr(cfg, "tokenHeader", "x-license-token");
        List<Integer> successCodes = configIntList(cfg, "successStatus", List.of(200));
        List<Integer> denyCodes = configIntList(cfg, "denyStatus", List.of(401, 402, 403));

        String token = request.header(tokenHeader);
        String body = buildRequestBody(token, request.path(), request.method());

        HttpRequest httpRequest = buildRequest(endpoint, httpMethod, token, body, tokenHeader);
        if (httpRequest == null) {
            return CompletableFuture.completedFuture(ValidationResult.denyDefault());
        }

        CompletableFuture<ValidationResult> future;
        if (request.rule().remoteConfig() != null) {
            ResilienceDecorators resilience = resilienceByRuleId.computeIfAbsent(
                    request.rule().id(),
                    id -> new ResilienceDecorators(id, request.rule().remoteConfig()));
            // Let network errors propagate through resilience so fail-open/fail-closed policy applies
            future = resilience.execute(() -> doRequest(httpRequest, successCodes, denyCodes));
        } else {
            future = doRequest(httpRequest, successCodes, denyCodes);
        }
        // For the no-resilience path, catch any network errors and return deny
        return future.exceptionally(err -> {
            log.warn("License backend unreachable (no resilience config): {}", err.getMessage());
            return ValidationResult.denyDefault();
        });
    }

    private CompletableFuture<ValidationResult> doRequest(HttpRequest req, List<Integer> successCodes, List<Integer> denyCodes) {
        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenApply(resp -> {
                    int status = resp.statusCode();
                    if (successCodes.contains(status)) {
                        return ValidationResult.allow();
                    }
                    if (denyCodes.contains(status)) {
                        return ValidationResult.deny(403, "{\"code\":\"LICENSE_DENIED\",\"message\":\"License backend denied the request\"}",
                                Map.of("content-type", "application/json"));
                    }
                    log.warn("Unexpected status {} from license backend", status);
                    return ValidationResult.denyDefault();
                })
                ;
    }

    private HttpRequest buildRequest(String endpoint, String method, String token, String body, String tokenHeader) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("content-type", "application/json");
            if (token != null) {
                builder.header(tokenHeader, token);
            }
            builder = switch (method) {
                case "GET" -> builder.GET();
                case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body));
                default -> {
                    log.warn("Unsupported HTTP method '{}'; defaulting to POST", method);
                    yield builder.POST(HttpRequest.BodyPublishers.ofString(body));
                }
            };
            return builder.build();
        } catch (Exception e) {
            log.error("Failed to build HTTP request for endpoint '{}': {}", endpoint, e.getMessage());
            return null;
        }
    }

    private static String buildRequestBody(String token, String path, String method) {
        String safeToken = token != null ? escape(token) : "";
        return "{\"token\":\"" + safeToken + "\",\"path\":\"" + escape(path) + "\",\"method\":\"" + escape(method) + "\"}";
    }

    private static String configStr(Map<String, Object> cfg, String key, String defaultValue) {
        Object val = cfg != null ? cfg.get(key) : null;
        return val != null ? val.toString() : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> configIntList(Map<String, Object> cfg, String key, List<Integer> defaultValue) {
        if (cfg == null) return defaultValue;
        Object val = cfg.get(key);
        if (val instanceof List<?> list && !list.isEmpty()) {
            try {
                return list.stream().map(o -> Integer.parseInt(o.toString())).toList();
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
