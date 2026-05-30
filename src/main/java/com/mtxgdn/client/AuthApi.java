package com.mtxgdn.client;

import com.google.gson.JsonObject;
import com.mtxgdn.common.ApiResponse;

public class AuthApi {

    private final ApiClient apiClient;

    public AuthApi(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public ApiResponse login(String username, String password) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);

        ApiResponse response = apiClient.post("/auth/login", body);

        if (response.isSuccess() && response.getToken() != null) {
            apiClient.setAuthToken(response.getToken());
        }

        return response;
    }

    public ApiResponse register(String username, String password) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);

        ApiResponse response = apiClient.post("/auth/register", body);

        if (response.isSuccess() && response.getToken() != null) {
            apiClient.setAuthToken(response.getToken());
        }

        return response;
    }

    public void logout() {
        apiClient.setAuthToken(null);
    }

    public boolean isLoggedIn() {
        return apiClient.isAuthenticated();
    }
}
