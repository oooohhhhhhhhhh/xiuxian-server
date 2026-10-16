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
