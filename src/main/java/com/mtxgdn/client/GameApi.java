package com.mtxgdn.client;

import com.google.gson.JsonObject;
import com.mtxgdn.common.ApiResponse;

public class GameApi {

    private final ApiClient apiClient;

    public GameApi(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public ApiResponse getPlayerInfo() {
        return apiClient.get("/game/player");
    }

    public ApiResponse createPlayer() {
        return apiClient.post("/game/player/create", new JsonObject());
    }

    public ApiResponse createPlayer(String name) {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        return apiClient.post("/game/player/create", body);
    }

    public ApiResponse breakthrough() {
        return apiClient.post("/game/realm/breakthrough", null);
    }

    public ApiResponse startCultivation() {
        return apiClient.post("/game/cultivate/start", null);
    }

    public ApiResponse stopCultivation() {
        return apiClient.post("/game/cultivate/stop", null);
    }

    public ApiResponse explore() {
        return apiClient.post("/game/exploration", null);
    }

    public ApiResponse getSecretRealmAreas() {
        return apiClient.get("/game/secret_realm/areas");
    }

    public ApiResponse enterSecretRealm(String area) {
        JsonObject body = new JsonObject();
        body.addProperty("area", area);
        return apiClient.post("/game/secret_realm/enter", body);
    }

    public ApiResponse useItem(String itemKey) {
        JsonObject body = new JsonObject();
        body.addProperty("itemKey", itemKey);
        return apiClient.post("/game/item/use", body);
    }

    public ApiResponse getInventory() {
        return apiClient.get("/game/inventory");
    }

    public ApiResponse getSkillList() {
        return apiClient.get("/game/skills");
    }

    public ApiResponse getMySkills() {
        return apiClient.get("/game/skill/my");
    }

    public ApiResponse learnSkill(Long skillId) {
        JsonObject body = new JsonObject();
        body.addProperty("skillId", skillId);
        return apiClient.post("/game/skill/learn", body);
    }

    public ApiResponse pvpChallenge(Long targetPlayerId) {
        JsonObject body = new JsonObject();
        body.addProperty("targetPlayerId", targetPlayerId);
        return apiClient.post("/game/pvp/challenge", body);
    }

    public ApiResponse getServerStatus() {
        return apiClient.get("/game/status");
    }

    public ApiResponse getAllPlayers(int limit, int offset) {
        return apiClient.get("/game/players?limit=" + limit + "&offset=" + offset);
    }
}
