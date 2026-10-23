package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 宗门查询域：宗门本体与成员的<b>只读</b>查询，外加宗门排行。
 *
 * <p>这些方法只做 {@code SELECT}，不写库、不开事务，因此被其它所有域共用
 * （成员域要用 {@link #getMember}、仓库域要用它、继位域要用 {@link #getSectMembers}……）。
 * 也正因为如此，它们必须<b>保持只读</b>：一旦这里出现写操作，调用方就无法判断
 * "我这一句会不会偷偷落库"。</p>
 *
 * <p>方法体、SQL 字符串、异常文案、返回语义都与拆分前逐字相同 —— 连每处 SQL 的
 * 字符串拼接方式都没有合并成常量，因为"纯结构重构"不该顺手改写查询。事务边界不受影响：
 * 本类不包含任何 {@code DatabaseManager.runTransaction} 调用，每条查询各自一个
 * 短连接，与拆分前完全一致。</p>
 */
final class SectQueryDomain {

    SectQueryDomain() {
    }

    // ==================== 宗门查询 ====================

    List<Sect> getAllSects() {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id " +
                "ORDER BY s.prestige DESC, s.level DESC";
        List<Sect> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(SectSupport.mapSect(rs));
        } catch (SQLException e) { throw new RuntimeException("查询宗门列表失败", e); }
        return result;
    }

    Sect getSectById(long sectId) {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id WHERE s.id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return SectSupport.mapSect(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门失败", e); }
        return null;
    }

    Sect getSectByName(String name) {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id WHERE s.name = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return SectSupport.mapSect(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门失败", e); }
        return null;
    }

    Sect getPlayerSect(long playerId) {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s JOIN sect_members sm ON s.id = sm.sect_id " +
                "LEFT JOIN players p ON s.leader_player_id = p.id WHERE sm.player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return SectSupport.mapSect(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询玩家宗门失败", e); }
        return null;
    }

    /** 宗门排行（按声望倒序取前 {@code limit} 个）。 */
    List<Sect> getTopSects(int limit) {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id " +
                "ORDER BY s.prestige DESC LIMIT ?";
        List<Sect> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(SectSupport.mapSect(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门排行失败", e); }
        return result;
    }

    // ==================== 成员查询 ====================

    List<SectMember> getSectMembers(long sectId) {
        String sql = "SELECT sm.*, p.name AS player_name, p.realm AS player_realm, p.level AS player_level " +
                "FROM sect_members sm JOIN players p ON sm.player_id = p.id " +
                "WHERE sm.sect_id = ? ORDER BY " +
                "CASE sm.role WHEN 'LEADER' THEN 0 WHEN 'VICE_LEADER' THEN 1 WHEN 'ELDER' THEN 2 WHEN 'INNER_MEMBER' THEN 3 ELSE 4 END, sm.joined_at";
        List<SectMember> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(SectSupport.mapMember(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门成员失败", e); }
        return result;
    }

    SectMember getMember(long sectId, long playerId) {
        String sql = "SELECT sm.*, p.name AS player_name, p.realm AS player_realm, p.level AS player_level " +
                "FROM sect_members sm JOIN players p ON sm.player_id = p.id " +
                "WHERE sm.sect_id = ? AND sm.player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return SectSupport.mapMember(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门成员失败", e); }
        return null;
    }

    SectMember getPlayerMember(long playerId) {
        String sql = "SELECT sm.*, p.name AS player_name, p.realm AS player_realm, p.level AS player_level " +
                "FROM sect_members sm JOIN players p ON sm.player_id = p.id WHERE sm.player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return SectSupport.mapMember(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门成员失败", e); }
        return null;
    }
}
