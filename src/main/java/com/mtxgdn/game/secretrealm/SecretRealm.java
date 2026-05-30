package com.mtxgdn.game.secretrealm;

import com.mtxgdn.util.LangManager;

public class SecretRealm {

    private String fullKey;
    private String name;
    private int requiredRealm;
    private long cooldownMs;
    private String description;

    protected SecretRealm(String namespace, String key, int requiredRealm,
                          long cooldownMs) {
        this.fullKey = namespace + ":" + key;
        this.name = "secretrealm." + key + ".name";
        this.requiredRealm = requiredRealm;
        this.cooldownMs = cooldownMs;
        this.description = "secretrealm." + key + ".desc";
        SecretRealmRegistry.register(this);
    }

    public String getFullKey() {
        return fullKey;
    }

    public String getNamespace() {
        int idx = fullKey.indexOf(':');
        return idx > 0 ? fullKey.substring(0, idx) : fullKey;
    }

    public String getKey() {
        int idx = fullKey.indexOf(':');
        return idx > 0 ? fullKey.substring(idx + 1) : fullKey;
    }

    public String getName() {
        return LangManager.get(name);
    }

    public int getRequiredRealm() {
        return requiredRealm;
    }

    public long getCooldownMs() {
        return cooldownMs;
    }

    public String getDescription() {
        return LangManager.get(description);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SecretRealm other)) return false;
        return fullKey.equals(other.fullKey);
    }

    @Override
    public int hashCode() {
        return fullKey.hashCode();
    }

    @Override
    public String toString() {
        return fullKey;
    }
}
