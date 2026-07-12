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

    private static final String CONFIG_FILE = "config/blacklist.yml";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path configPath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Yaml yaml;

    public BlacklistService() {
        this.configPath = Paths.get(CONFIG_FILE);
        copyDefaultIfMissing();
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Representer representer = new Representer(options);
        representer.getPropertyUtils().setSkipMissingProperties(true);
        this.yaml = new Yaml(representer, options);
    }

    private void copyDefaultIfMissing() {
        if (Files.exists(configPath)) return;
        try {
            Files.createDirectories(configPath.getParent());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config/blacklist.yml")) {
                if (in != null) {
                    Files.copy(in, configPath);
                } else {
                    Files.createFile(configPath);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("初始化黑名单配置文件失败", e);
        }
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
        Object qq = map.get("qqNumber");
        b.setQqNumber(qq != null && !String.valueOf(qq).isEmpty() ? String.valueOf(qq) : null);
        Object uid = map.get("userId");
        b.setUserId(uid != null ? Long.valueOf(String.valueOf(uid)) : null);
        b.setReason(map.get("reason") != null ? String.valueOf(map.get("reason")) : "");
        Object bb = map.get("bannedBy");
        b.setBannedBy(bb != null ? Long.valueOf(String.valueOf(bb)) : null);
        b.setCreatedAt(map.get("createdAt") != null ? String.valueOf(map.get("createdAt")) : "");
        return b;
    }

    /**
     * 检查QQ号是否在黑名单中（直接ban QQ号，或通过userId关联的绑定账号）。
     */
    public boolean isBlacklisted(String qqNumber) {
        List<Map<String, Object>> list = loadRawList();
        for (Map<String, Object> entry : list) {
            // 直接ban的QQ号
            Object eqq = entry.get("qqNumber");
            if (eqq != null && qqNumber.equals(String.valueOf(eqq))) {
                return true;
            }
            // 通过userId ban（查绑定）
            Object uid = entry.get("userId");
            if (uid != null) {
                QqBinding binding = new QqBindingService().findByQq(qqNumber);
                if (binding != null && String.valueOf(binding.getUserId()).equals(String.valueOf(uid))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 获取黑名单条目对应的所有需要禁言的QQ号。
     * - QQ号模式：直接返回该QQ号
     * - 用户ID模式：通过绑定表找到该用户绑定的QQ号
     */
    public List<String> resolveQqNumbers(Blacklist b) {
        List<String> result = new ArrayList<>();
        if (b.isQqMode()) {
            result.add(b.getQqNumber());
        } else if (b.isUserMode()) {
            QqBinding binding = new QqBindingService().findByUserId(b.getUserId());
            if (binding != null) {
                result.add(binding.getQqNumber());
            }
        }
        return result;
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
            Object eqq = entry.get("qqNumber");
            if (eqq != null && qqNumber.equals(String.valueOf(eqq))) {
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

    /**
     * 添加到黑名单。qqNumber 和 userId 二选一。
     * - 填 qqNumber：直接 ban 该QQ号
     * - 填 userId：ban 该用户，自动通过绑定表找到其QQ号禁言
     */
    public void addToBlacklist(String qqNumber, Long userId, String reason, Long bannedBy) {
        boolean hasQq = qqNumber != null && !qqNumber.isEmpty();
        boolean hasUid = userId != null;
        if (hasQq && hasUid) {
            throw new RuntimeException("qqNumber 和 userId 只能二选一");
        }
        if (!hasQq && !hasUid) {
            throw new RuntimeException("qqNumber 和 userId 必须填写其中一个");
        }

        if (hasQq && isBlacklisted(qqNumber)) {
            throw new RuntimeException("该QQ号已在黑名单中");
        }
        if (hasUid && isBlacklistedByUserId(userId)) {
            throw new RuntimeException("该用户已在黑名单中");
        }

        List<Map<String, Object>> list = loadRawList();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("qqNumber", hasQq ? qqNumber : null);
        entry.put("userId", hasUid ? userId : null);
        entry.put("reason", reason != null ? reason : "");
        entry.put("bannedBy", bannedBy);
        entry.put("createdAt", LocalDateTime.now().format(DTF));
        list.add(entry);
        saveRawList(list);
    }

    public void removeFromBlacklist(String qqNumber) {
        List<Map<String, Object>> list = loadRawList();
        boolean removed = list.removeIf(e -> {
            Object eqq = e.get("qqNumber");
            return eqq != null && qqNumber.equals(String.valueOf(eqq));
        });
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
