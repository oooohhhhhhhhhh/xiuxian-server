package com.mtxgdn.game.entity;

import com.google.gson.reflect.TypeToken;
import com.google.gson.Gson;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

public class RedeemCode {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REDEEMED = "redeemed";
    public static final String STATUS_EXPIRED = "expired";

    private static final Gson gson = new Gson();
    private static final Type ITEMS_MAP_TYPE = new TypeToken<Map<String, Integer>>() {}.getType();

    private long id;
    private String code;
    private String name;
    private String itemsJson;
    private long gold;
    private long spiritStones;
    private long exp;
    private int maxUses;
    private int currentUses;
    private String status;
    private String expiresAt;
    private String createdBy;
    private String createdAt;
    private String updatedAt;

    // ---- getters/setters ----

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getItemsJson() { return itemsJson; }
    public void setItemsJson(String itemsJson) { this.itemsJson = itemsJson; }

    public Map<String, Integer> getItems() {
        if (itemsJson == null || itemsJson.isEmpty()) return new LinkedHashMap<>();
        try {
            Map<String, Integer> map = gson.fromJson(itemsJson, ITEMS_MAP_TYPE);
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public void setItems(Map<String, Integer> items) {
        this.itemsJson = gson.toJson(items);
    }

    public long getGold() { return gold; }
    public void setGold(long gold) { this.gold = gold; }

    public long getSpiritStones() { return spiritStones; }
    public void setSpiritStones(long spiritStones) { this.spiritStones = spiritStones; }

    public long getExp() { return exp; }
    public void setExp(long exp) { this.exp = exp; }

    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }

    public int getCurrentUses() { return currentUses; }
    public void setCurrentUses(int currentUses) { this.currentUses = currentUses; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isActive() { return STATUS_ACTIVE.equals(status); }
    public boolean isRedeemed() { return STATUS_REDEEMED.equals(status); }
    public boolean isExpired() { return STATUS_EXPIRED.equals(status); }

    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
