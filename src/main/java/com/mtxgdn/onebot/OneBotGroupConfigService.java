package com.mtxgdn.onebot;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.representer.Representer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class OneBotGroupConfigService {

    private static final String CONFIG_FILE = "onebot_group_config.yml";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path configPath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Yaml yaml;

    public OneBotGroupConfigService() {
        this.configPath = Paths.get(CONFIG_FILE);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Representer representer = new Representer(options);
        representer.getPropertyUtils().setSkipMissingProperties(true);
        this.yaml = new Yaml(representer, options);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadRawList() {
        lock.readLock().lock();
        try {
            if (!Files.exists(configPath)) {
                return new ArrayList<>();
            }
            Map<String, Object> data = yaml.load(Files.newInputStream(configPath));
            if (data == null || data.get("groups") == null) {
                return new ArrayList<>();
            }
            return (List<Map<String, Object>>) data.get("groups");
        } catch (IOException e) {
            throw new RuntimeException("读取群组配置文件失败", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void saveRawList(List<Map<String, Object>> entries) {
        lock.writeLock().lock();
        try {
            Files.createDirectories(configPath.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("groups", entries);
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(configPath), StandardCharsets.UTF_8)) {
                yaml.dump(root, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("写入群组配置文件失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public OneBotGroupConfig findByGroupId(Long groupId) {
        List<Map<String, Object>> list = loadRawList();
        for (Map<String, Object> entry : list) {
            Object gid = entry.get("groupId");
            if (gid != null && String.valueOf(groupId).equals(String.valueOf(gid))) {
                return fromMap(entry);
            }
        }
        return null;
    }

    public List<OneBotGroupConfig> getAllConfigs() {
        List<Map<String, Object>> rawList = loadRawList();
        List<OneBotGroupConfig> list = new ArrayList<>();
        for (Map<String, Object> entry : rawList) {
            list.add(fromMap(entry));
        }
        return list;
    }

    public OneBotGroupConfig getOrCreateConfig(Long groupId) {
        OneBotGroupConfig config = findByGroupId(groupId);
        if (config == null) {
            config = new OneBotGroupConfig(groupId);
            saveConfig(config);
        }
        return config;
    }

    public void saveConfig(OneBotGroupConfig config) {
        List<Map<String, Object>> list = loadRawList();
        Iterator<Map<String, Object>> it = list.iterator();
        boolean found = false;
        while (it.hasNext()) {
            Map<String, Object> entry = it.next();
            Object gid = entry.get("groupId");
            if (gid != null && String.valueOf(config.getGroupId()).equals(String.valueOf(gid))) {
                // 更新
                entry.put("autoMuteEnabled", config.isAutoMuteEnabled());
                entry.put("muteDurationDays", config.getMuteDurationDays());
                entry.put("updatedAt", LocalDateTime.now().format(DTF));
                found = true;
                break;
            }
        }
        if (!found) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("groupId", config.getGroupId());
            entry.put("autoMuteEnabled", config.isAutoMuteEnabled());
            entry.put("muteDurationDays", config.getMuteDurationDays());
            String now = LocalDateTime.now().format(DTF);
            entry.put("createdAt", now);
            entry.put("updatedAt", now);
            list.add(entry);
        }
        saveRawList(list);
    }

    public void setAutoMute(Long groupId, boolean enabled) {
        OneBotGroupConfig config = getOrCreateConfig(groupId);
        config.setAutoMuteEnabled(enabled);
        saveConfig(config);
    }

    public void setMuteDuration(Long groupId, int days) {
        OneBotGroupConfig config = getOrCreateConfig(groupId);
        config.setMuteDurationDays(days);
        saveConfig(config);
    }

    public void deleteConfig(Long groupId) {
        List<Map<String, Object>> list = loadRawList();
        boolean removed = list.removeIf(e -> {
            Object gid = e.get("groupId");
            return gid != null && String.valueOf(groupId).equals(String.valueOf(gid));
        });
        if (!removed) {
            throw new RuntimeException("群组配置不存在");
        }
        saveRawList(list);
    }

    public boolean isAutoMuteEnabled(Long groupId) {
        OneBotGroupConfig config = findByGroupId(groupId);
        return config != null && config.isAutoMuteEnabled();
    }

    public int getMuteDuration(Long groupId) {
        OneBotGroupConfig config = findByGroupId(groupId);
        return config != null ? config.getMuteDurationDays() : 29;
    }

    private OneBotGroupConfig fromMap(Map<String, Object> map) {
        OneBotGroupConfig c = new OneBotGroupConfig();
        Object gid = map.get("groupId");
        c.setGroupId(gid != null ? Long.valueOf(String.valueOf(gid)) : null);
        Object ame = map.get("autoMuteEnabled");
        c.setAutoMuteEnabled(ame instanceof Boolean ? (Boolean) ame : Boolean.parseBoolean(String.valueOf(ame)));
        Object mdd = map.get("muteDurationDays");
        c.setMuteDurationDays(mdd != null ? Integer.parseInt(String.valueOf(mdd)) : 29);
        c.setCreatedAt(map.get("createdAt") != null ? String.valueOf(map.get("createdAt")) : "");
        c.setUpdatedAt(map.get("updatedAt") != null ? String.valueOf(map.get("updatedAt")) : "");
        return c;
    }
}
