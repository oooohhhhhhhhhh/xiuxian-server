package com.mtxgdn.game.config;

import com.google.gson.Gson;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.RealmConfigFile;
import com.mtxgdn.util.AppConfig;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;

public class GameConfigLoader {

    private static final Gson gson = new Gson();
    private static final String REALM_CONFIG_PATH = "game/realm_config.json";

    private static volatile RealmConfigFile realmConfigFile;
    private static volatile List<RealmConfig> realmConfigs;

    /**
     * 主动释放配置文件到 jar 所在目录的 config/ 下，供用户修改
     */
    public static void extractConfigs() {
        Path jarDir = AppConfig.getJarDir();
        Path target = jarDir.resolve("config").resolve(REALM_CONFIG_PATH);
        if (!Files.exists(target)) {
            try (InputStream in = GameConfigLoader.class.getClassLoader().getResourceAsStream(REALM_CONFIG_PATH)) {
                if (in != null) {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[GameConfig] 已释放配置文件: " + target.toAbsolutePath());
                }
            } catch (Exception e) {
                System.err.println("[GameConfig] 释放 " + REALM_CONFIG_PATH + " 失败: " + e.getMessage());
            }
        }
    }

    private GameConfigLoader() {
    }

    public static int getRealmTotal() {
        return realmConfigs.size();
    }

    public static int getCultivationPerSecond(int realmId) {
        return (int) (getCultivationBaseValue() * getCultivationMultiplier(realmId));
    }

    public static List<RealmConfig> getRealmConfigs() {
        if (realmConfigs == null) {
            synchronized (GameConfigLoader.class) {
                if (realmConfigs == null) {
                    loadRealmConfig();
                }
            }
        }
        return realmConfigs;
    }

    public static RealmConfig getRealmConfig(int realmId, int subRealm) {
        for (RealmConfig config : getRealmConfigs()) {
            if (config.getId() == realmId && config.getSubRealm() == subRealm) {
                return config;
            }
        }
        return null;
    }

    public static RealmConfig getNextRealmConfig(int currentRealmId, int currentSubRealm) {
        getRealmConfigs();

        RealmConfig nextSub = getRealmConfig(currentRealmId, currentSubRealm + 1);
        if (nextSub != null) {
            return nextSub;
        }

        RealmConfig nextMain = null;
        int nextRealmId = currentRealmId + 1;
        while (nextMain == null && nextRealmId <= 10) {
            nextMain = getRealmConfig(nextRealmId, 0);
            nextRealmId++;
        }
        return nextMain;
    }

    public static boolean isMaxRealm(int realmId, int subRealm) {
        RealmConfig current = getRealmConfig(realmId, subRealm);
        return current != null && current.isMaxRealm();
    }

    public static double getCultivationMultiplier(int realmId) {
        if (realmConfigFile == null) {
            loadRealmConfig();
        }
        double[] multipliers = realmConfigFile.getCultivationPerSecond().getRealmMultiplier();
        if (realmId >= 0 && realmId < multipliers.length) {
            return multipliers[realmId];
        }
        return 1.0;
    }

    public static int getCultivationBaseValue() {
        if (realmConfigFile == null) {
            loadRealmConfig();
        }
        return realmConfigFile.getCultivationPerSecond().getBaseValue();
    }

    private static void loadRealmConfig() {
        Path jarDir = AppConfig.getJarDir();
        Path externalPath = jarDir.resolve("config").resolve(REALM_CONFIG_PATH);

        // 优先从外部 config 目录加载，若不存在则从 classpath 加载
        if (Files.exists(externalPath)) {
            try (InputStream is = new FileInputStream(externalPath.toFile())) {
                Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                realmConfigFile = gson.fromJson(reader, RealmConfigFile.class);
                realmConfigs = Collections.unmodifiableList(realmConfigFile.getRealms());
                return;
            } catch (Exception e) {
                System.err.println("[GameConfig] 从外部加载失败，回退到内置配置: " + e.getMessage());
            }
        }

        try (InputStream is = GameConfigLoader.class.getClassLoader().getResourceAsStream(REALM_CONFIG_PATH)) {
            if (is == null) {
                throw new RuntimeException("找不到境界配置文件: " + REALM_CONFIG_PATH);
            }
            Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            realmConfigFile = gson.fromJson(reader, RealmConfigFile.class);
            realmConfigs = Collections.unmodifiableList(realmConfigFile.getRealms());
        } catch (Exception e) {
            throw new RuntimeException("加载境界配置失败: " + e.getMessage(), e);
        }
    }
}
