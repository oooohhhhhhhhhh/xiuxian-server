package com.mtxgdn.game.secretrealm;

import com.mtxgdn.util.GameLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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

    /** 取消注册一个秘境。 */
    public static void unregister(SecretRealm realm) {
        if (realm == null) return;
        String fullKey = realm.getFullKey();
        if (realms.remove(fullKey) != null) {
            LOG.debug("取消注册秘境: " + fullKey);
        }
    }

    public static SecretRealm get(String fullKey) {
        return realms.get(fullKey);
    }

    public static SecretRealm resolve(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        SecretRealm byKey = realms.get(input);
        if (byKey != null) {
            return byKey;
        }
        try {
            int index = Integer.parseInt(input);
            return getByIndex(index);
        } catch (NumberFormatException e) {
        }
        for (SecretRealm realm : realms.values()) {
            String translatedName = realm.getName();
            if (translatedName != null && translatedName.equals(input)) {
                return realm;
            }
        }
        String lower = input.toLowerCase();
        for (SecretRealm realm : realms.values()) {
            String translatedName = realm.getName();
            if (translatedName != null && translatedName.toLowerCase().contains(lower)) {
                return realm;
            }
            if (realm.getFullKey().toLowerCase().contains(lower)) {
                return realm;
            }
        }
        return null;
    }

    private static List<SecretRealm> getSortedRealms() {
        return realms.values().stream()
                .sorted(Comparator.comparingInt(SecretRealm::getRequiredRealm))
                .toList();
    }

    public static SecretRealm getByIndex(int index) {
        List<SecretRealm> list = getSortedRealms();
        if (index >= 1 && index <= list.size()) {
            return list.get(index - 1);
        }
        return null;
    }

    public static List<SecretRealm> getByRealm(int requiredRealm) {
        return getSortedRealms().stream()
                .filter(realm -> realm.getRequiredRealm() <= requiredRealm)
                .toList();
    }

    public static List<SecretRealm> getRaidRealms(int requiredRealm) {
        List<SecretRealm> result = new ArrayList<>();
        for (SecretRealm realm : realms.values()) {
            if (realm.isRaid() && realm.getRequiredRealm() <= requiredRealm) {
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
