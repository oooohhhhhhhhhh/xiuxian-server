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

    public ApiResponse getRealmConfig() {
        return apiClient.get("/game/realm/config");
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

    public ApiResponse heal() {
        return apiClient.post("/game/heal", null);
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

    public ApiResponse addItem(String itemKey, int quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("itemKey", itemKey);
        body.addProperty("quantity", quantity);
        return apiClient.post("/game/item/add", body);
    }

    public ApiResponse getInventory() {
        return apiClient.get("/game/inventory");
    }

    public ApiResponse getItemRegistry() {
        return apiClient.get("/game/item/registry");
    }

    public ApiResponse getEquipment() {
        return apiClient.get("/game/equipment");
    }

    public ApiResponse equipItem(String itemKey, String slot) {
        JsonObject body = new JsonObject();
        body.addProperty("itemKey", itemKey);
        body.addProperty("slot", slot);
        return apiClient.post("/game/equipment/equip", body);
    }

    public ApiResponse unequipItem(String slot) {
        JsonObject body = new JsonObject();
        body.addProperty("slot", slot);
        return apiClient.post("/game/equipment/unequip", body);
    }

    public ApiResponse enhanceEquipment(String slot) {
        JsonObject body = new JsonObject();
        body.addProperty("slot", slot);
        return apiClient.post("/game/equipment/enhance", body);
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

    public ApiResponse getTechniques() {
        return apiClient.get("/game/techniques");
    }

    public ApiResponse getMyTechniques() {
        return apiClient.get("/game/technique/my");
    }

    public ApiResponse learnTechnique(Long techniqueId) {
        JsonObject body = new JsonObject();
        body.addProperty("techniqueId", techniqueId);
        return apiClient.post("/game/technique/learn", body);
    }

    public ApiResponse equipTechnique(Long techniqueId) {
        JsonObject body = new JsonObject();
        body.addProperty("techniqueId", techniqueId);
        return apiClient.post("/game/technique/equip", body);
    }

    public ApiResponse unequipTechnique(Long techniqueId) {
        JsonObject body = new JsonObject();
        body.addProperty("techniqueId", techniqueId);
        return apiClient.post("/game/technique/unequip", body);
    }

    public ApiResponse upgradeTechnique(Long techniqueId) {
        JsonObject body = new JsonObject();
        body.addProperty("techniqueId", techniqueId);
        return apiClient.post("/game/technique/upgrade", body);
    }

    public ApiResponse getRecipes(String category) {
        if (category == null || category.isEmpty()) {
            return apiClient.get("/game/crafting/recipes");
        }
        return apiClient.get("/game/crafting/recipes?category=" + category);
    }

    public ApiResponse craftItem(Long recipeId) {
        JsonObject body = new JsonObject();
        body.addProperty("recipeId", recipeId);
        return apiClient.post("/game/crafting/craft", body);
    }

    public ApiResponse getDailyInfo() {
        return apiClient.get("/game/daily");
    }

    public ApiResponse morningCultivation() {
        return apiClient.post("/game/daily/morning_cultivation", null);
    }

    public ApiResponse getMarketListings() {
        return apiClient.get("/game/market");
    }

    public ApiResponse listItem(String itemKey, int quantity, long priceSpiritStones) {
        JsonObject body = new JsonObject();
        body.addProperty("itemKey", itemKey);
        body.addProperty("quantity", quantity);
        body.addProperty("priceSpiritStones", priceSpiritStones);
        return apiClient.post("/game/market/list", body);
    }

    public ApiResponse buyItem(Long listingId) {
        JsonObject body = new JsonObject();
        body.addProperty("listingId", listingId);
        return apiClient.post("/game/market/buy", body);
    }

    public ApiResponse cancelListing(Long listingId) {
        JsonObject body = new JsonObject();
        body.addProperty("listingId", listingId);
        return apiClient.post("/game/market/cancel", body);
    }

    public ApiResponse getMyListings() {
        return apiClient.get("/game/market/my_listings");
    }

    public ApiResponse getRanking(String type, int limit) {
        return apiClient.get("/game/rank?type=" + type + "&limit=" + limit);
    }

    public ApiResponse getSpiritualRoots() {
        return apiClient.get("/game/spiritual_roots");
    }

    public ApiResponse searchPlayers(String name) {
        return apiClient.get("/game/players/search?name=" + name);
    }

    public ApiResponse getFriendList() {
        return apiClient.get("/game/friend/list");
    }

    public ApiResponse getFriendPending() {
        return apiClient.get("/game/friend/pending");
    }

    public ApiResponse addFriend(Long targetPlayerId) {
        JsonObject body = new JsonObject();
        body.addProperty("targetPlayerId", targetPlayerId);
        return apiClient.post("/game/friend/add", body);
    }

    public ApiResponse acceptFriend(Long requesterPlayerId) {
        JsonObject body = new JsonObject();
        body.addProperty("requesterPlayerId", requesterPlayerId);
        return apiClient.post("/game/friend/accept", body);
    }

    public ApiResponse removeFriend(Long friendPlayerId) {
        JsonObject body = new JsonObject();
        body.addProperty("friendPlayerId", friendPlayerId);
        return apiClient.post("/game/friend/remove", body);
    }

    public ApiResponse createSect(String name, String description) {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("description", description);
        return apiClient.post("/game/sect/create", body);
    }

    public ApiResponse joinSect(Long sectId) {
        return apiClient.post("/game/sect/join/" + sectId, null);
    }

    public ApiResponse getSectMembers() {
        return apiClient.get("/game/sect/members");
    }

    public ApiResponse getSectApplications() {
        return apiClient.get("/game/sect/applications");
    }

    public ApiResponse approveApplication(Long appId) {
        return apiClient.post("/game/sect/approve/" + appId, null);
    }

    public ApiResponse rejectApplication(Long appId) {
        return apiClient.post("/game/sect/reject/" + appId, null);
    }

    public ApiResponse leaveSect() {
        return apiClient.post("/game/sect/leave", null);
    }

    public ApiResponse kickMember(Long targetPlayerId) {
        return apiClient.post("/game/sect/kick/" + targetPlayerId, null);
    }

    public ApiResponse appointMember(Long targetPlayerId, String role) {
        JsonObject body = new JsonObject();
        body.addProperty("targetPlayerId", targetPlayerId);
        body.addProperty("role", role);
        return apiClient.post("/game/sect/appoint", body);
    }

    public ApiResponse getSectWarehouse() {
        return apiClient.get("/game/sect/warehouse");
    }

    public ApiResponse donateToWarehouse(String itemKey, int quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("itemKey", itemKey);
        body.addProperty("quantity", quantity);
        return apiClient.post("/game/sect/donate", body);
    }

    public ApiResponse takeFromWarehouse(String itemKey, int quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("itemKey", itemKey);
        body.addProperty("quantity", quantity);
        return apiClient.post("/game/sect/take", body);
    }

    public ApiResponse disbandSect() {
        return apiClient.post("/game/sect/disband", null);
    }

    public ApiResponse levelUpSect() {
        return apiClient.post("/game/sect/levelup", null);
    }

    public ApiResponse transferSect(Long targetPlayerId) {
        return apiClient.post("/game/sect/transfer/" + targetPlayerId, null);
    }

    public ApiResponse declareWar(Long targetSectId) {
        return apiClient.post("/game/sect/war/" + targetSectId, null);
    }

    public ApiResponse getSectTop() {
        return apiClient.get("/game/sect/top");
    }

    public ApiResponse getMapSurroundings() {
        return apiClient.get("/game/map");
    }

    public ApiResponse travelTo(Long locationId) {
        return apiClient.post("/game/map/travel/" + locationId, null);
    }

    public ApiResponse getMapLocations() {
        return apiClient.get("/game/map/locations");
    }

    public ApiResponse getAllTitles() {
        return apiClient.get("/game/title/all");
    }

    public ApiResponse getMyTitles() {
        return apiClient.get("/game/title/my");
    }

    public ApiResponse getActiveTitle() {
        return apiClient.get("/game/title/active");
    }

    public ApiResponse equipTitle(String titleKey) {
        JsonObject body = new JsonObject();
        body.addProperty("titleKey", titleKey);
        return apiClient.post("/game/title/equip", body);
    }

    public ApiResponse unequipTitle() {
        return apiClient.post("/game/title/unequip", null);
    }

    public ApiResponse createTeam() {
        return apiClient.post("/game/team/create", null);
    }

    public ApiResponse invitePlayer(Long targetPlayerId) {
        JsonObject body = new JsonObject();
        body.addProperty("targetPlayerId", targetPlayerId);
        return apiClient.post("/game/team/invite", body);
    }

    public ApiResponse acceptInvite() {
        return apiClient.post("/game/team/accept", null);
    }

    public ApiResponse leaveTeam() {
        return apiClient.post("/game/team/leave", null);
    }

    public ApiResponse getTeamInfo() {
        return apiClient.get("/game/team/info");
    }

    public ApiResponse getRaidRealms() {
        return apiClient.get("/game/raid/realms");
    }

    public ApiResponse enterRaid(String areaName) {
        JsonObject body = new JsonObject();
        body.addProperty("areaName", areaName);
        return apiClient.post("/game/raid/enter", body);
    }

    public ApiResponse getWorldChat(int limit) {
        return apiClient.get("/game/chat/world?limit=" + limit);
    }

    public ApiResponse getPrivateChat(Long targetPlayerId, int limit) {
        return apiClient.get("/game/chat/private/" + targetPlayerId + "?limit=" + limit);
    }

    public ApiResponse sendWorldChat(String content) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        return apiClient.post("/game/chat/world", body);
    }

    public ApiResponse sendPrivateChat(Long targetPlayerId, String content) {
        JsonObject body = new JsonObject();
        body.addProperty("targetPlayerId", targetPlayerId);
        body.addProperty("content", content);
        return apiClient.post("/game/chat/private", body);
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