package com.mtxgdn.game.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一个野外分片的<b>持久化状态</b>（对应磁盘上一个 JSON 文件）。
 *
 * <p>这里刻意<b>只存可变状态</b>：谁探索过、资源还剩多少、什么时候补货。
 * 地形本身（节点名、描述、危险度、连通关系）一概不存 —— 它们是
 * {@code (worldSeed, chunkX, chunkY)} 的纯函数，每次都由
 * {@link WildernessTerrain} 现算。因此这个文件很小（通常 1~3 KB），
 * 而且<b>永远不会和地形对不上</b>：地形根本没有第二份副本可以走样。</p>
 *
 * <p>字段用 String 作为槽位键（{@code "0"}~{@code "15"}）而不是 int：
 * JSON 的对象键本来就是字符串，这样文件内容与内存结构一一对应，
 * 管理员打开文件看到的就是程序读到的，不存在隐式转换。</p>
 */
public class WildernessChunk {

    /** 分片坐标。 */
    public int chunkX;
    public int chunkY;

    /** 生成参数指纹（世界种子 + 分片边长）。对不上说明管理员改过世界种子，本文件作废。 */
    public long seedHash;
    /** 生成时的分片边长，便于排查"改过 chunkSize 之后文件对不上"的问题。 */
    public int chunkSize;

    /** 是否为野外出入口分片（永不回收：回收了玩家就进不来野外了）。 */
    public boolean gateway;
    /** 是否已固化。只要有人探索过这里就置位，且<b>永不回退</b>，也没有管理员后门。 */
    public boolean pinned;

    public long generatedAt;
    public long lastActiveAt;
    /** 下次原地补货时间。注意是"补货"不是"换地形"：到点只把 remaining 补回 total。 */
    public long restockAt;

    /** 资源是否已经抽过。用它而不是"resources 为空"来判断：资源池全空时也该只抽一次。 */
    public boolean resourcesRolled;

    /** 槽位 → 物品 key → 库存。只记录被抽中过的资源，没抽中的槽位不会出现。 */
    public Map<String, Map<String, Resource>> resources = new LinkedHashMap<>();
    /** 槽位 → 探索过该节点的玩家 id。非空即意味着 {@link #pinned}。 */
    public Map<String, List<Long>> exploredBy = new LinkedHashMap<>();

    /** 单个资源的库存：{@code total} 是本次补货周期的上限，{@code remaining} 是当前剩余。 */
    public static class Resource {
        public int total;
        public int remaining;

        public Resource() {
        }

        public Resource(int total, int remaining) {
            this.total = total;
            this.remaining = remaining;
        }
    }

    public static String slotKey(int slot) {
        return String.valueOf(slot);
    }

    // ==================== 资源 ====================

    public Map<String, Resource> resourcesOf(int slot) {
        if (resources == null) {
            resources = new LinkedHashMap<>();
        }
        return resources.computeIfAbsent(slotKey(slot), k -> new LinkedHashMap<>());
    }

    /** 该节点当前可采的资源（物品 key → 剩余量）；空表表示此地无资源或已采空。 */
    public Map<String, Integer> remainingOf(int slot) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (resources == null) {
            return result;
        }
        Map<String, Resource> slotResources = resources.get(slotKey(slot));
        if (slotResources == null) {
            return result;
        }
        for (Map.Entry<String, Resource> entry : slotResources.entrySet()) {
            if (entry.getValue() != null && entry.getValue().remaining > 0) {
                result.put(entry.getKey(), entry.getValue().remaining);
            }
        }
        return result;
    }

    /**
     * 扣减库存；库存不足时返回实际扣减量（0 表示已被别人采空）。
     *
     * <p>{@code synchronized} 是这里<b>唯一的并发保护</b>：分片对象由 store 统一缓存、
     * 全进程共享同一份，所以"读取剩余量 → 扣减"这一对动作在分片粒度上天然互斥，
     * 两个玩家同时点采集不会把同一份资源发两次。数据库版本靠
     * {@code UPDATE ... WHERE remaining >= ?} 做这件事，文件版本没有那层条件判断可用，
     * 于是把锁下沉到分片对象上 —— 粒度比整张表更细。</p>
     */
    public synchronized int consume(int slot, String itemKey, int amount) {
        Map<String, Resource> slotResources = resourcesOf(slot);
        Resource resource = slotResources.get(itemKey);
        if (resource == null || resource.remaining <= 0) {
            return 0;
        }
        int taken = Math.min(amount, resource.remaining);
        resource.remaining -= taken;
        return taken;
    }

    /** 把库存加回去（发物品失败时回滚用，避免资源凭空消失）。 */
    public synchronized void refund(int slot, String itemKey, int amount) {
        if (amount <= 0) {
            return;
        }
        Resource resource = resourcesOf(slot).get(itemKey);
        if (resource != null) {
            resource.remaining += amount;
        }
    }

    /** 原地补货：把各资源的剩余量补回上限，不动地形、不动探索记录。 */
    public synchronized boolean restock(long now, long intervalSeconds) {
        boolean changed = false;
        if (resources != null) {
            for (Map<String, Resource> slotResources : resources.values()) {
                for (Resource resource : slotResources.values()) {
                    if (resource != null && resource.remaining < resource.total) {
                        resource.remaining = resource.total;
                        changed = true;
                    }
                }
            }
        }
        restockAt = now + intervalSeconds;
        return changed;
    }

    // ==================== 探索 ====================

    public Set<Long> exploredBySlot(int slot) {
        if (exploredBy == null) {
            exploredBy = new LinkedHashMap<>();
        }
        List<Long> players = exploredBy.get(slotKey(slot));
        return players == null ? Set.of() : new TreeSet<>(players);
    }

    public boolean isExploredBy(int slot, long playerId) {
        if (exploredBy == null) {
            return false;
        }
        List<Long> players = exploredBy.get(slotKey(slot));
        return players != null && players.contains(playerId);
    }

    /**
     * 记录"某玩家首次抵达某节点"，返回是否<b>新增</b>（false 表示重复抵达）。
     *
     * <p>一旦有探索记录就置 {@link #pinned}：这是"已探索的内容永远不会被回收"的落点，
     * 而且只在这里置位、没有任何地方会清除它。</p>
     */
    public synchronized boolean markExplored(int slot, long playerId) {
        if (exploredBy == null) {
            exploredBy = new LinkedHashMap<>();
        }
        List<Long> players = exploredBy.computeIfAbsent(slotKey(slot), k -> new ArrayList<>());
        if (players.contains(playerId)) {
            return false;
        }
        players.add(playerId);
        pinned = true;
        return true;
    }

    public boolean hasAnyExploration() {
        if (exploredBy == null || exploredBy.isEmpty()) {
            return false;
        }
        for (List<Long> players : exploredBy.values()) {
            if (players != null && !players.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 已探索过的节点数（用于管理台统计）。 */
    public int exploredNodeCount() {
        if (exploredBy == null) {
            return 0;
        }
        int count = 0;
        for (List<Long> players : exploredBy.values()) {
            if (players != null && !players.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** 去重后的探索者数量。 */
    public int explorerCount() {
        if (exploredBy == null) {
            return 0;
        }
        Set<Long> all = new TreeSet<>();
        for (List<Long> players : exploredBy.values()) {
            if (players != null) {
                all.addAll(players);
            }
        }
        return all.size();
    }

    /** 该分片是否有任何玩家正站在上面（有则不允许回收）。 */
    public long chunkKey() {
        return WildernessTerrain.chunkKey(chunkX, chunkY);
    }
}
