package com.mtxgdn.db;

import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

public class DatabaseManager {

    private static final String DB_TYPE = AppConfig.get("database.type", "mysql");
    private static final boolean IS_SQLITE = "sqlite".equalsIgnoreCase(DB_TYPE);

    private static final String DB_URL = IS_SQLITE
            ? "jdbc:sqlite:" + AppConfig.get("database.sqlite_path", "xiuxian.db")
            : AppConfig.get("database.url", "jdbc:mysql://localhost:3306/xiuxian?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci");
    private static final String DB_USER = IS_SQLITE ? "" : AppConfig.get("database.username", "root");
    private static final String DB_PASSWORD = IS_SQLITE ? "" : AppConfig.get("database.password", "root");

    private static volatile HikariDataSource dataSource;

    private static HikariDataSource getDataSource() {
        if (dataSource == null) {
            synchronized (DatabaseManager.class) {
                if (dataSource == null) {
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(DB_URL);

                    if (IS_SQLITE) {
                        config.setMaximumPoolSize(1);
                        config.setMinimumIdle(1);
                        config.setConnectionTimeout(10000);
                        config.addDataSourceProperty("journal_mode", "WAL");
                        config.addDataSourceProperty("busy_timeout", "5000");
                    } else {
                        config.setUsername(DB_USER);
                        config.setPassword(DB_PASSWORD);
                        config.setMaximumPoolSize(10);
                        config.setMinimumIdle(2);
                        config.setIdleTimeout(30000);
                        config.setConnectionTimeout(10000);
                        config.addDataSourceProperty("cachePrepStmts", "true");
                        config.addDataSourceProperty("prepStmtCacheSize", "250");
                        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                        config.addDataSourceProperty("useUnicode", "true");
                        config.addDataSourceProperty("characterEncoding", "UTF-8");
                        config.setConnectionInitSql("SET NAMES utf8mb4");
                    }

                    dataSource = new HikariDataSource(config);
                }
            }
        }
        return dataSource;
    }

    public static boolean isSqlite() {
        return IS_SQLITE;
    }

    public static Connection getConnection() throws SQLException {
        Connection conn = getDataSource().getConnection();
        if (IS_SQLITE) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
        }
        return conn;
    }

    public static void initTable() {
        String pkMySql = "BIGINT AUTO_INCREMENT PRIMARY KEY";
        String pkSqlite = "INTEGER PRIMARY KEY AUTOINCREMENT";
        String pk = IS_SQLITE ? pkSqlite : pkMySql;

        String tsDefault = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
        String tsUpdate = IS_SQLITE ? tsDefault : "TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP";
        String boolType = IS_SQLITE ? "INTEGER DEFAULT 0" : "BOOLEAN DEFAULT FALSE";

        String userTableSql = "CREATE TABLE IF NOT EXISTS users (" +
                "id " + pk + ", " +
                "username VARCHAR(64) NOT NULL UNIQUE, " +
                "password VARCHAR(256) NOT NULL, " +
                "email VARCHAR(128) DEFAULT '', " +
                "created_at " + tsDefault +
                ")";

        String verificationCodesTableSql = "CREATE TABLE IF NOT EXISTS verification_codes (" +
                "id " + pk + ", " +
                "email VARCHAR(128) NOT NULL, " +
                "code VARCHAR(6) NOT NULL, " +
                "expires_at TIMESTAMP NOT NULL, " +
                "created_at " + tsDefault +
                ")";

        String playersTableSql = "CREATE TABLE IF NOT EXISTS players (" +
                "id " + pk + ", " +
                "user_id BIGINT NOT NULL UNIQUE, " +
                "name VARCHAR(64) NOT NULL, " +
                "spiritual_root VARCHAR(16) DEFAULT 'FIRE', " +
                "level INT DEFAULT 1, " +
                "experience BIGINT DEFAULT 0, " +
                "realm INT DEFAULT 0, " +
                "sub_realm INT DEFAULT 0, " +
                "hp INT DEFAULT 100, " +
                "max_hp INT DEFAULT 100, " +
                "mp INT DEFAULT 50, " +
                "max_mp INT DEFAULT 50, " +
                "attack INT DEFAULT 10, " +
                "defense INT DEFAULT 5, " +
                "speed INT DEFAULT 5, " +
                "spirit INT DEFAULT 5, " +
                "gold BIGINT DEFAULT 0, " +
                "cultivation_progress INT DEFAULT 0, " +
                "is_cultivating " + boolType + ", " +
                "cultivation_start_time BIGINT DEFAULT 0, " +
                "last_secret_realm_time BIGINT DEFAULT 0, " +
                "last_exploration_time BIGINT DEFAULT 0, " +
                "tutorial_step INT DEFAULT 0, " +
                "tutorial_tips INT DEFAULT 0, " +
                "last_offline_time BIGINT DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate;

        if (!IS_SQLITE) {
            playersTableSql += ", FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE";
        }
        playersTableSql += ")";

        String playersItemsTableSql = "CREATE TABLE IF NOT EXISTS players_items (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "quantity INT NOT NULL DEFAULT 1, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (IS_SQLITE ? "" : "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, ") +
                "UNIQUE (player_id, item_key)" +
                ")";

        String playersEquipmentTableSql = "CREATE TABLE IF NOT EXISTS players_equipment (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "slot VARCHAR(32) NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (IS_SQLITE ? "" : "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, ") +
                "UNIQUE (player_id, slot)" +
                ")";

        String skillsTableSql = "CREATE TABLE IF NOT EXISTS skills (" +
                "id " + pk + ", " +
                "name VARCHAR(64) NOT NULL, " +
                "description VARCHAR(256) DEFAULT '', " +
                "required_realm INT DEFAULT 0, " +
                "learn_cost_gold BIGINT DEFAULT 0, " +
                "learn_cost_spirit_stones BIGINT DEFAULT 0, " +
                "damage INT DEFAULT 0, " +
                "mp_cost INT DEFAULT 0, " +
                "cooldown_seconds INT DEFAULT 0, " +
                "skill_type INT DEFAULT 0, " +
                "heal_amount INT DEFAULT 0, " +
                "max_level INT DEFAULT 10, " +
                "created_at " + tsDefault +
                ")";

        String playersSkillsTableSql = "CREATE TABLE IF NOT EXISTS players_skills (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "skill_id BIGINT NOT NULL, " +
                "level INT NOT NULL DEFAULT 1, " +
                "proficiency INT DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (IS_SQLITE ? "" : "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE, ") +
                "UNIQUE (player_id, skill_id)" +
                ")";

        String tradeListingsTableSql = "CREATE TABLE IF NOT EXISTS trade_listings (" +
                "id " + pk + ", " +
                "seller_player_id BIGINT NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "quantity INT NOT NULL, " +
                "price_spirit_stones BIGINT NOT NULL, " +
                "fee BIGINT NOT NULL DEFAULT 0, " +
                "status VARCHAR(16) DEFAULT 'active', " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                (IS_SQLITE ? "" : ", FOREIGN KEY (seller_player_id) REFERENCES players(id) ON DELETE CASCADE") +
                ")";

        String playerDailyTableSql = "CREATE TABLE IF NOT EXISTS player_daily (" +
                "player_id BIGINT PRIMARY KEY, " +
                "last_morning_cultivation VARCHAR(16), " +
                "consecutive_days INT DEFAULT 0, " +
                "total_active_days INT DEFAULT 0, " +
                "last_daily_reset DATE DEFAULT (CURRENT_DATE), " +
                "exploration_count INT DEFAULT 0, " +
                "battle_count INT DEFAULT 0, " +
                "secret_realm_count INT DEFAULT 0, " +
                "skill_learn_count INT DEFAULT 0, " +
                "exploration_rewarded INT DEFAULT 0, " +
                "battle_rewarded INT DEFAULT 0, " +
                "secret_realm_rewarded INT DEFAULT 0, " +
                "skill_learn_rewarded INT DEFAULT 0, " +
                "resonance7_awarded INT DEFAULT 0, " +
                "resonance30_awarded INT DEFAULT 0" +
                (IS_SQLITE ? "" : ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE") +
                ")";

        String qqBindingsTableSql = "CREATE TABLE IF NOT EXISTS qq_bindings (" +
                "id " + pk + ", " +
                "qq_number VARCHAR(32) NOT NULL UNIQUE, " +
                "user_id BIGINT NOT NULL UNIQUE, " +
                "created_at " + tsDefault +
                (IS_SQLITE ? "" : ", FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE") +
                ")";

        String rolesTableSql = "CREATE TABLE IF NOT EXISTS roles (" +
                "id " + pk + ", " +
                "name VARCHAR(32) NOT NULL UNIQUE, " +
                "display_name VARCHAR(64) NOT NULL, " +
                "level INT NOT NULL, " +
                "created_at " + tsDefault +
                ")";

        String permissionsTableSql = "CREATE TABLE IF NOT EXISTS permissions (" +
                "id " + pk + ", " +
                "code VARCHAR(64) NOT NULL UNIQUE, " +
                "name VARCHAR(64) NOT NULL, " +
                "category VARCHAR(32) NOT NULL, " +
                "created_at " + tsDefault +
                ")";

        String rolePermissionsTableSql = "CREATE TABLE IF NOT EXISTS role_permissions (" +
                "id " + pk + ", " +
                "role_name VARCHAR(32) NOT NULL, " +
                "permission_code VARCHAR(64) NOT NULL, " +
                "created_at " + tsDefault + ", " +
                "UNIQUE (role_name, permission_code)" +
                ")";

        String userRolesTableSql = "CREATE TABLE IF NOT EXISTS user_roles (" +
                "id " + pk + ", " +
                "user_id BIGINT NOT NULL, " +
                "role_name VARCHAR(32) NOT NULL, " +
                "created_at " + tsDefault + ", " +
                (IS_SQLITE ? "" : "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, ") +
                "UNIQUE (user_id, role_name)" +
                ")";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(userTableSql);
            stmt.execute(verificationCodesTableSql);
            stmt.execute(playersTableSql);
            stmt.execute(playersItemsTableSql);
            stmt.execute(playersEquipmentTableSql);
            stmt.execute(skillsTableSql);
            stmt.execute(playersSkillsTableSql);
            stmt.execute(tradeListingsTableSql);
            stmt.execute(playerDailyTableSql);
            stmt.execute(qqBindingsTableSql);
            stmt.execute(rolesTableSql);
            stmt.execute(permissionsTableSql);
            stmt.execute(rolePermissionsTableSql);
            stmt.execute(userRolesTableSql);
        } catch (SQLException e) {
            throw new RuntimeException("创建数据库表失败", e);
        }

        PermissionService.initDefaultData();
    }

    public static Map<String, Integer> clearPlayerData() {
        Map<String, Integer> result = new LinkedHashMap<>();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            if (!IS_SQLITE) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
            }
            int ps = stmt.executeUpdate("DELETE FROM players_skills");
            int pi = stmt.executeUpdate("DELETE FROM players_items");
            int pe = stmt.executeUpdate("DELETE FROM players_equipment");
            int pd = stmt.executeUpdate("DELETE FROM player_daily");
            int tl = stmt.executeUpdate("DELETE FROM trade_listings");
            int pl = stmt.executeUpdate("DELETE FROM players");
            if (!IS_SQLITE) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
            }

            result.put("players_skills", ps);
            result.put("players_items", pi);
            result.put("players_equipment", pe);
            result.put("player_daily", pd);
            result.put("trade_listings", tl);
            result.put("players", pl);
        } catch (SQLException e) {
            throw new RuntimeException("清除玩家数据失败", e);
        }

        return result;
    }

    public static Map<String, Integer> resetAllData() {
        Map<String, Integer> result = new LinkedHashMap<>();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            if (!IS_SQLITE) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
            }
            int ps = stmt.executeUpdate("DELETE FROM players_skills");
            int pi = stmt.executeUpdate("DELETE FROM players_items");
            int pe = stmt.executeUpdate("DELETE FROM players_equipment");
            int pd = stmt.executeUpdate("DELETE FROM player_daily");
            int tl = stmt.executeUpdate("DELETE FROM trade_listings");
            int pl = stmt.executeUpdate("DELETE FROM players");
            int qb = stmt.executeUpdate("DELETE FROM qq_bindings");
            int ur = stmt.executeUpdate("DELETE FROM user_roles");
            int rp = stmt.executeUpdate("DELETE FROM role_permissions");
            int roles = stmt.executeUpdate("DELETE FROM roles");
            int perm = stmt.executeUpdate("DELETE FROM permissions");
            int vc = stmt.executeUpdate("DELETE FROM verification_codes");
            if (!IS_SQLITE) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
            }

            result.put("players_skills", ps);
            result.put("players_items", pi);
            result.put("players_equipment", pe);
            result.put("player_daily", pd);
            result.put("trade_listings", tl);
            result.put("players", pl);
            result.put("qq_bindings", qb);
            result.put("user_roles", ur);
            result.put("role_permissions", rp);
            result.put("roles", roles);
            result.put("permissions", perm);
            result.put("verification_codes", vc);
        } catch (SQLException e) {
            throw new RuntimeException("重置数据库失败", e);
        }

        PermissionService.initDefaultData();
        return result;
    }
}
