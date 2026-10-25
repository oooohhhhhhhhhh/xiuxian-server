package com.mtxgdn.game.service;

import com.mtxgdn.db.SectQueryDAO;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;

import java.util.List;

/**
 * 宗门查询域：宗门本体与成员的<b>只读</b>查询，外加宗门排行。
 *
 * <p>这些方法只做 {@code SELECT}，不写库、不开事务，因此被其它所有域共用
 * （成员域要用 {@link #getMember}、仓库域要用它、继位域要用 {@link #getSectMembers}……）。
 * 也正因为如此，它们必须<b>保持只读</b>：一旦这里出现写操作，调用方就无法判断
 * "我这一句会不会偷偷落库"。</p>
 *
 * <p>方法体、返回语义与拆分前逐字相同 —— SQL 已全部搬进 {@link SectQueryDAO}
 * （每条 SQL 只在 DAO 里写一份，自取连接版整体委托给 Connection 版），
 * 本类只负责把调用转给 DAO。事务边界不受影响：本类不包含任何
 * {@code DatabaseManager.runTransaction} 调用，每条查询各自一个短连接，
 * 与拆分前完全一致。</p>
 *
 * <p><b>DAO 的每个方法都有 Connection 版</b>：这八个查询被五个域共用，
 * 别域的事务块随时可能想复用同一条语句，那时必须传 {@code conn}，
 * 自己开连接会撞 {@code SQLITE_BUSY_SNAPSHOT}。</p>
 */
final class SectQueryDomain {

    /** {@code sects} / {@code sect_members} 的只读查询入口（无状态，可共享）。 */
    private static final SectQueryDAO QUERY_DAO = new SectQueryDAO();

    SectQueryDomain() {
    }

    // ==================== 宗门查询 ====================

    List<Sect> getAllSects() {
        return QUERY_DAO.getAllSects();
    }

    Sect getSectById(long sectId) {
        return QUERY_DAO.getSectById(sectId);
    }

    Sect getSectByName(String name) {
        return QUERY_DAO.getSectByName(name);
    }

    Sect getPlayerSect(long playerId) {
        return QUERY_DAO.getPlayerSect(playerId);
    }

    /** 宗门排行（按声望倒序取前 {@code limit} 个）。 */
    List<Sect> getTopSects(int limit) {
        return QUERY_DAO.getTopSects(limit);
    }

    // ==================== 成员查询 ====================

    List<SectMember> getSectMembers(long sectId) {
        return QUERY_DAO.getSectMembers(sectId);
    }

    SectMember getMember(long sectId, long playerId) {
        return QUERY_DAO.getMember(sectId, playerId);
    }

    SectMember getPlayerMember(long playerId) {
        return QUERY_DAO.getPlayerMember(playerId);
    }
}
