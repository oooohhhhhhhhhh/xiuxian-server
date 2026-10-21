package com.mtxgdn.playerdata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个玩家的完整数据快照（一用户一个文件）。
 *
 * <p>tables 中每个 key 对应数据库中的一张表，value 为该玩家在这张表中的全部行。
 * 行内值均已转换为可 JSON 序列化的基本类型（时间戳转为毫秒数）。</p>
 */
public class PlayerData {

    /** 玩家 id */
    public long playerId;

    /** 所属用户 id */
    public long userId;

    /** 玩家名 */
    public String playerName;

    /** 导出时间（毫秒时间戳） */
    public long exportedAt;

    /**
     * 数据格式版本。将来若改变行内值的编码方式（例如时间戳标签格式），
     * 靠这个字段决定还原时要不要做兼容转换。
     */
    public int schemaVersion = 1;

    /** 导出时的总行数（跨所有表）。还原前用它做一次粗校验。 */
    public int rowCount;

    /**
     * {@link #tables} 的 SHA-256 摘要（十六进制）。
     *
     * <p>这是把这份文件从"只写不读的快照"变成<b>可信备份</b>的关键：写入后立刻回读校验、
     * 还原前再校验一次，文件被截断、被手工改坏或磁盘写坏都会在还原前被发现，
     * 而不是把半份数据灌回数据库。</p>
     */
    public String checksum;

    /** 表名 → 行数据列表 */
    public Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();

    public PlayerData() {
    }

    public PlayerData(long playerId, long userId, String playerName, long exportedAt) {
        this.playerId = playerId;
        this.userId = userId;
        this.playerName = playerName;
        this.exportedAt = exportedAt;
    }
}
