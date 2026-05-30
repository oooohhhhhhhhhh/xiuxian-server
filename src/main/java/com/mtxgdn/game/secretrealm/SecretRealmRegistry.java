package com.mtxgdn.game.secretrealm;

import com.mtxgdn.util.GameLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SecretRealmRegistry {

    private static final GameLogger LOG = GameLogger.getLogger(SecretRealmRegistry.class);
    private static final Map<String, SecretRealm> realms = new LinkedHashMap<>();

    private SecretRealmRegistry() {
    }

    public static void register(SecretRealm realm) {
        String fullKey = realm.getFullKey();
        if (realms.containsKey(fullKey)) {
            SecretRealm old = realms.get(fullKey);
            LOG.warn("秘境覆盖: " + fullKey + " (" + old + " -> " + realm + ")");
        }
        realms.put(fullKey, realm);
        LOG.debug("注册秘境: " + fullKey);
    }

    public static SecretRealm get(String fullKey) {
        return realms.get(fullKey);
    }

    public static List<SecretRealm> getByRealm(int requiredRealm) {
        List<SecretRealm> result = new ArrayList<>();
        for (SecretRealm realm : realms.values()) {
            if (realm.getRequiredRealm() <= requiredRealm) {
                result.add(realm);
            }
        }
        return result;
    }

    public static Collection<SecretRealm> getAll() {
        return Collections.unmodifiableCollection(realms.values());
    }

    public static int count() {
        return realms.size();
    }

    public static boolean contains(String fullKey) {
        return realms.containsKey(fullKey);
    }

    public static void clear() {
        realms.clear();
    }
}
