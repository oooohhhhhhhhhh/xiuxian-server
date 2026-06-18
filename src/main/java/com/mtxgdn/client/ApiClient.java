package com.mtxgdn.client;

import com.google.gson.JsonObject;
import com.mtxgdn.common.ApiResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ApiClient {

    private final HttpClient httpClient;
    private final ApiConfig config;
    private String authToken;

    public ApiClient() {
        this.config = ApiConfig.getInstance();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void setAuthToken(String token) {
        this.authToken = token;
    }

    public String getAuthToken() {
        return authToken;
    }

    public boolean isAuthenticated() {
        return authToken != null && !authToken.isEmpty();
    }

    public ApiResponse get(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .GET();

        if (authToken != null) {
            builder.header("Authorization", "Bearer " + authToken);
        }

        return execute(builder.build());
    }

    public ApiResponse post(String path, JsonObject body) {
        HttpRequest.BodyPublisher bodyPublisher;
        if (body != null) {
            bodyPublisher = HttpRequest.BodyPublishers.ofString(body.toString());
        } else {
            bodyPublisher = HttpRequest.BodyPublishers.noBody();
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .POST(bodyPublisher);

        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        if (authToken != null) {
            builder.header("Authorization", "Bearer " + authToken);
        }

        return execute(builder.build());
    }

    public ApiResponse put(String path, JsonObject body) {
        HttpRequest.BodyPublisher bodyPublisher;
        if (body != null) {
            bodyPublisher = HttpRequest.BodyPublishers.ofString(body.toString());
        } else {
            bodyPublisher = HttpRequest.BodyPublishers.noBody();
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .PUT(bodyPublisher);

        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        if (authToken != null) {
            builder.header("Authorization", "Bearer " + authToken);
        }

        return execute(builder.build());
    }

    private ApiResponse execute(HttpRequest request) {
        String method = request.method();
        String url = request.uri().toString();
        String body = request.bodyPublisher().map(p -> {
            return "(body)";
        }).orElse("(nobody)");

        long start = System.currentTimeMillis();
        System.out.println("[ApiClient] >>> " + method + " " + url + " " + body);

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;
            String responseBody = response.body();
            System.out.println("[ApiClient] <<< " + response.statusCode() + " (" + elapsed + "ms) " +
                    (responseBody != null ? responseBody.substring(0, Math.min(200, responseBody.length())) : "(null)"));
            if (responseBody == null || responseBody.isEmpty()) {
                return buildError(response.statusCode(), "服务器无响应");
            }
            return ApiResponse.fromJson(responseBody);
        } catch (java.net.ConnectException e) {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[ApiClient] <<< ConnectException (" + elapsed + "ms): " + e.getMessage());
            return buildError(503, "无法连接到服务器，请检查网络连接");
        } catch (java.net.http.HttpTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[ApiClient] <<< TimeoutException (" + elapsed + "ms): " + e.getMessage());
            return buildError(504, "请求超时，请稍后重试");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[ApiClient] <<< Exception (" + elapsed + "ms): " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return buildError(500, "网络请求失败: " + e.getMessage());
        }
    }

    private ApiResponse buildError(int code, String message) {
        ApiResponse resp = new ApiResponse();
        resp.setCode(code);
        resp.setMessage(message);
        return resp;
    }
}
