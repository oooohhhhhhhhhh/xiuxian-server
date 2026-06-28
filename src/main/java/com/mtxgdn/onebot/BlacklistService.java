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

public class BlacklistService {

    private static final String CONFIG_FILE = "blacklist.yml";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path configPath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Yaml yaml;

    public BlacklistService() {
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
            if (data == null || data.get("entries") == null) {
                return new ArrayList<>();
            }
            return (List<Map<String, Object>>) data.get("entries");
        } catch (IOException e) {
            throw new RuntimeException("读取黑名单配置文件失败", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void saveRawList(List<Map<String, Object>> entries) {
        lock.writeLock().lock();
        try {
            Files.createDirectories(configPath.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("entries", entries);
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(configPath), StandardCharsets.UTF_8)) {
                yaml.dump(root, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("写入黑名单配置文件失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Blacklist fromMap(Map<String, Object> map) {
        Blacklist b = new Blacklist();
        b.setQqNumber(String.valueOf(map.get("qqNumber")));
        Object uid = map.get("userId");
        b.setUserId(uid != null ? Long.valueOf(String.valueOf(uid)) : null);
        b.setReason(map.get("reason") != null ? String.valueOf(map.get("reason")) : "");
        Object bb = map.get("bannedBy");
        b.setBannedBy(bb != null ? Long.valueOf(String.valueOf(bb)) : null);
        b.setCreatedAt(map.get("createdAt") != null ? String.valueOf(map.get("createdAt")) : "");
        return b;
    }

    private Map<String, Object> toMap(Blacklist b) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("qqNumber", b.getQqNumber());
        map.put("userId", b.getUserId());
        map.put("reason", b.getReason());
        map.put("bannedBy", b.getBannedBy());
        map.put("createdAt", b.getCreatedAt());
        return map;
    }

    public boolean isBlacklisted(String qqNumber) {
        List<Map<String, Object>> list = loadRawList();
        for (Map<String, Object> entry : list) {
            if (qqNumber.equals(String.valueOf(entry.get("qqNumber")))) {
                return true;
            }
        }
        return false;
    }

    public boolean isBlacklistedByUserId(Long userId) {
        if (userId == null) return false;
        List<Map<String, Object>> list = loadRawList();
        for (Map<String, Object> entry : list) {
            Object uid = entry.get("userId");
            if (uid != null && String.valueOf(userId).equals(String.valueOf(uid))) {
                return true;
            }
        }
        return false;
    }

    public Blacklist findByQq(String qqNumber) {
        List<Map<String, Object>> list = loadRawList();
        for (Map<String, Object> entry : list) {
            if (qqNumber.equals(String.valueOf(entry.get("qqNumber")))) {
                return fromMap(entry);
            }
        }
        return null;
    }

    public Blacklist findByUserId(Long userId) {
        if (userId == null) return null;
        List<Map<String, Object>> list = loadRawList();
        for (Map<String, Object> entry : list) {
            Object uid = entry.get("userId");
            if (uid != null && String.valueOf(userId).equals(String.valueOf(uid))) {
                return fromMap(entry);
            }
        }
        return null;
    }

    public List<Blacklist> getAllBlacklist() {
        List<Map<String, Object>> rawList = loadRawList();
        List<Blacklist> list = new ArrayList<>();
        for (Map<String, Object> entry : rawList) {
            list.add(fromMap(entry));
        }
        return list;
    }

    public void addToBlacklist(String qqNumber, Long userId, String reason, Long bannedBy) {
        if (isBlacklisted(qqNumber)) {
            throw new RuntimeException("该QQ号已在黑名单中");
        }
        List<Map<String, Object>> list = loadRawList();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("qqNumber", qqNumber);
        entry.put("userId", userId);
        entry.put("reason", reason != null ? reason : "");
        entry.put("bannedBy", bannedBy);
        entry.put("createdAt", LocalDateTime.now().format(DTF));
        list.add(entry);
        saveRawList(list);
    }

    public void removeFromBlacklist(String qqNumber) {
        List<Map<String, Object>> list = loadRawList();
        boolean removed = list.removeIf(e -> qqNumber.equals(String.valueOf(e.get("qqNumber"))));
        if (!removed) {
            throw new RuntimeException("该QQ号不在黑名单中");
        }
        saveRawList(list);
    }

    public void removeFromBlacklistByUserId(Long userId) {
        List<Map<String, Object>> list = loadRawList();
        boolean removed = list.removeIf(e -> {
            Object uid = e.get("userId");
            return uid != null && String.valueOf(userId).equals(String.valueOf(uid));
        });
        if (!removed) {
            throw new RuntimeException("该用户不在黑名单中");
        }
        saveRawList(list);
    }
}
