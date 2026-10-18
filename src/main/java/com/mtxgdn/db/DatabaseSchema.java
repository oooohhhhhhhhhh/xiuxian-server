package com.mtxgdn.db;

public class DatabaseSchema {

    public static String createUsersTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS users (" +
                "id " + pk + ", " +
                "username VARCHAR(64) NOT NULL UNIQUE, " +
                "password VARCHAR(256) NOT NULL, " +
                "email VARCHAR(128) DEFAULT '', " +
                "failed_attempts INT DEFAULT 0, " +
                "locked_until TIMESTAMP DEFAULT NULL, " +
                "created_at " + tsDefault +
                ")";
    }

    public static String createTechniquesTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS techniques (" +
                "id " + pk + ", " +
                "name VARCHAR(64) NOT NULL, " +
                "description VARCHAR(256) DEFAULT '', " +
                "required_realm INT DEFAULT 0, " +
                "learn_cost_gold BIGINT DEFAULT 0, " +
                "learn_cost_spirit_stones BIGINT DEFAULT 0, " +
                "upgrade_base_cost_gold INT DEFAULT 100, " +
                "upgrade_base_cost_spirit_stones INT DEFAULT 50, " +
                "type VARCHAR(16) NOT NULL DEFAULT 'CULTIVATION', " +
                "max_level INT DEFAULT 10, " +
                "hp_bonus INT DEFAULT 0, " +
                "mp_bonus INT DEFAULT 0, " +
                "attack_bonus INT DEFAULT 0, " +
                "defense_bonus INT DEFAULT 0, " +
                "speed_bonus INT DEFAULT 0, " +
                "spirit_bonus INT DEFAULT 0, " +
                "cultivation_speed_bonus DOUBLE DEFAULT 0, " +
                "exp_bonus DOUBLE DEFAULT 0, " +
                "combat_damage_bonus DOUBLE DEFAULT 0, " +
                "damage_reduction DOUBLE DEFAULT 0, " +
                "created_at " + tsDefault +
                ")";
    }

    public static String createPlayersTechniquesTable(String pk, String tsDefault, String tsUpdate,
                                                      String boolType, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS players_techniques (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "technique_id BIGINT NOT NULL, " +
                "level INT NOT NULL DEFAULT 1, " +
                "proficiency INT DEFAULT 0, " +
                "is_equipped " + boolType + ", " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (dialect.supportsForeignKeysInCreateTable()
                        ? "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, FOREIGN KEY (technique_id) REFERENCES techniques(id) ON DELETE CASCADE, "
                        : "") +
                "UNIQUE (player_id, technique_id)" +
                ")";
    }

    public static String createRecipesTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS recipes (" +
                "id " + pk + ", " +
                "name VARCHAR(64) NOT NULL, " +
                "description VARCHAR(256) DEFAULT '', " +
                "category VARCHAR(16) NOT NULL DEFAULT 'PILL', " +
                "required_realm INT DEFAULT 0, " +
                "result_item_key VARCHAR(128) NOT NULL, " +
                "result_quantity INT DEFAULT 1, " +
                "material1_key VARCHAR(128), " +
                "material1_count INT DEFAULT 0, " +
                "material2_key VARCHAR(128), " +
                "material2_count INT DEFAULT 0, " +
                "material3_key VARCHAR(128), " +
                "material3_count INT DEFAULT 0, " +
                "cost_gold BIGINT DEFAULT 0, " +
                "cost_spirit_stones BIGINT DEFAULT 0, " +
                "success_rate DOUBLE DEFAULT 0.8, " +
                "min_exp_gain BIGINT DEFAULT 0, " +
                "max_exp_gain BIGINT DEFAULT 0, " +
                "created_at " + tsDefault +
                ")";
    }

    public static String createVerificationCodesTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS verification_codes (" +
                "id " + pk + ", " +
                "email VARCHAR(128) NOT NULL, " +
                "code VARCHAR(6) NOT NULL, " +
                "expires_at TIMESTAMP NOT NULL, " +
                "sent_at TIMESTAMP DEFAULT NULL, " +
                "created_at " + tsDefault +
                ")";
    }

    public static String createPlayersTable(String pk, String tsDefault, String tsUpdate,
                                           String boolType, DatabaseDialect dialect) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS players (")
           .append("id ").append(pk).append(", ")
           .append("user_id BIGINT NOT NULL UNIQUE, ")
           .append("name VARCHAR(64) NOT NULL, ")
           .append("spiritual_root VARCHAR(64) DEFAULT 'FIRE', ")
           .append("level INT DEFAULT 1, ")
           .append("experience BIGINT DEFAULT 0, ")
           .append("realm INT DEFAULT 0, ")
           .append("sub_realm INT DEFAULT 0, ")
           .append("hp INT DEFAULT 100, ")
           .append("max_hp INT DEFAULT 100, ")
           .append("mp INT DEFAULT 50, ")
           .append("max_mp INT DEFAULT 50, ")
           .append("attack INT DEFAULT 10, ")
           .append("defense INT DEFAULT 5, ")
           .append("speed INT DEFAULT 5, ")
           .append("spirit INT DEFAULT 5, ")
           .append("gold BIGINT DEFAULT 0, ")
           .append("cultivation_progress INT DEFAULT 0, ")
           .append("is_cultivating ").append(boolType).append(", ")
           .append("cultivation_start_time BIGINT DEFAULT 0, ")
           .append("last_secret_realm_time BIGINT DEFAULT 0, ")
           .append("last_exploration_time BIGINT DEFAULT 0, ")
           .append("tutorial_step INT DEFAULT 0, ")
           .append("tutorial_tips INT DEFAULT 0, ")
           .append("last_offline_time BIGINT DEFAULT 0, ")
           // 最近活跃时间（毫秒）。与 last_offline_time 不同，它覆盖所有通道
           // （QQ / REST / WebSocket / MC），是"弃坑判定"的唯一依据，见 SectService 继位逻辑。
           .append("last_active_time BIGINT DEFAULT 0, ")
           // 突破失败增益（pity）：连续渡劫失败的累计次数，成功率按次数加成，成功后清零。
           // 存"次数"而不是"加成值"，这样调整 breakthrough.pity_bonus 后老数据自动跟着变。
           .append("breakthrough_pity INT DEFAULT 0, ")
           // 凝实（极境）层数：0..3，渡劫成功后清零。
           .append("consolidation INT DEFAULT 0, ")
           .append("current_location_id BIGINT DEFAULT 1, ")
           .append("last_gather_time BIGINT DEFAULT 0, ")
           .append("created_at ").append(tsDefault).append(", ")
           .append("updated_at ").append(tsUpdate);
        if (dialect.supportsForeignKeysInCreateTable()) {
            sql.append(", FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE");
        }
        sql.append(")");
        return sql.toString();
    }

    public static String createPlayersItemsTable(String pk, String tsDefault, String tsUpdate,
                                                 DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS players_items (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "quantity BIGINT NOT NULL DEFAULT 1, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (dialect.supportsForeignKeysInCreateTable()
                        ? "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, "
                        : "") +
                "UNIQUE (player_id, item_key)" +
                ")";
    }

    public static String createPlayersEquipmentTable(String pk, String tsDefault, String tsUpdate,
                                                     DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS players_equipment (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "slot VARCHAR(32) NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "enhance_level INT DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (dialect.supportsForeignKeysInCreateTable()
                        ? "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, "
                        : "") +
                "UNIQUE (player_id, slot)" +
                ")";
    }

    public static String createSkillsTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS skills (" +
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
    }

    public static String createPlayersSkillsTable(String pk, String tsDefault, String tsUpdate,
                                                  DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS players_skills (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "skill_id BIGINT NOT NULL, " +
                "level INT NOT NULL DEFAULT 1, " +
                "proficiency INT DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (dialect.supportsForeignKeysInCreateTable()
                        ? "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE, "
                        : "") +
                "UNIQUE (player_id, skill_id)" +
                ")";
    }

    public static String createTradeListingsTable(String pk, String tsDefault, String tsUpdate,
                                                  DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS trade_listings (" +
                "id " + pk + ", " +
                "seller_player_id BIGINT NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "quantity INT NOT NULL, " +
                "price_spirit_stones BIGINT NOT NULL, " +
                "fee BIGINT NOT NULL DEFAULT 0, " +
                "status VARCHAR(16) DEFAULT 'active', " +
                // 挂单到期时间。NULL 表示历史遗留挂单（永不过期），
                // 新挂单一律写入具体时间，由 TradeService 定时回收。
                "expires_at TIMESTAMP DEFAULT NULL, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (seller_player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    /**
     * 坊市成交价格历史。每笔成交记录一行，用于给玩家展示"近期均价/最低/最高"，
     * 解决此前玩家挂单完全靠猜的问题。
     * <p>
     * {@code unit_price} 是单价（总价 ÷ 数量）：同一物品的挂单数量不同，
     * 只有单价之间才可比。
     */
    public static String createTradePriceHistoryTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS trade_price_history (" +
                "id " + pk + ", " +
                "item_key VARCHAR(128) NOT NULL, " +
                "unit_price BIGINT NOT NULL, " +
                "quantity INT NOT NULL, " +
                "total_price BIGINT NOT NULL, " +
                "sold_at " + tsDefault +
                ")";
    }

    /** 价格历史按 (物品, 时间) 查询的索引，避免行情查询全表扫描。 */
    public static String createTradePriceHistoryIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_trade_price_item_time "
                + "ON trade_price_history (item_key, sold_at)";
    }

    /**
     * 坊市挂单表的索引。
     * <p>
     * 此前 {@code trade_listings} <b>一个索引都没有</b>，而它有两处高频访问：
     * <ul>
     *   <li>到期回收任务每 60 秒执行一次
     *       {@code WHERE status='active' AND expires_at <= ?}；</li>
     *   <li>{@code /坊市} 每次执行都要
     *       {@code WHERE status='active' AND (expires_at IS NULL OR expires_at > ?)}
     *       并 {@code ORDER BY created_at DESC}。</li>
     * </ul>
     * 没有索引时两者都是全表扫描，挂单量增长后每 60 秒一次的扫描会持续占用数据库。
     * <p>
     * 两个索引分别覆盖「按状态+到期时间扫描」（回收任务、在售筛选）
     * 与「按卖家查自己的挂单」（{@code /我的上架}）。
     */
    public static String createTradeListingsStatusExpiresIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_trade_listings_status_expires "
                + "ON trade_listings (status, expires_at)";
    }

    public static String createTradeListingsSellerStatusIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_trade_listings_seller_status "
                + "ON trade_listings (seller_player_id, status)";
    }

    /**
     * player_buffs 按到期时间的索引。
     * <p>
     * 过期增益回收任务查询的是 {@code WHERE expire_time <= ?}，而该表原本一个索引都没有，
     * 于是每次执行都是全表扫描。加了索引之后，扫描代价只与"已过期的行数"相关，
     * 与表总量无关。
     * <p>
     * 另注：该任务已从每秒一次降为低频兜底（见 {@code BuffService}），
     * 活跃玩家的过期增益由访问时的惰性过期即时处理。
     */
    public static String createPlayerBuffsExpireIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_player_buffs_expire "
                + "ON player_buffs (expire_time)";
    }

    public static String createPlayerDailyTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS player_daily (" +
                "player_id BIGINT PRIMARY KEY, " +
                "last_morning_cultivation VARCHAR(16), " +
                "consecutive_days INT DEFAULT 0, " +
                "total_active_days INT DEFAULT 0, " +
                "last_daily_reset DATE DEFAULT (CURRENT_DATE), " +
                "exploration_count INT DEFAULT 0, " +
                "battle_count INT DEFAULT 0, " +
                "secret_realm_count INT DEFAULT 0, " +
                "skill_learn_count INT DEFAULT 0, " +
                "pvp_count INT DEFAULT 0, " +
                "exploration_rewarded INT DEFAULT 0, " +
                "battle_rewarded INT DEFAULT 0, " +
                "secret_realm_rewarded INT DEFAULT 0, " +
                "skill_learn_rewarded INT DEFAULT 0, " +
                "resonance7_awarded INT DEFAULT 0, " +
                "resonance30_awarded INT DEFAULT 0" +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    public static String createQqBindingsTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS qq_bindings (" +
                "id " + pk + ", " +
                "qq_number VARCHAR(32) NOT NULL UNIQUE, " +
                "user_id BIGINT NOT NULL UNIQUE, " +
                "created_at " + tsDefault +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    /**
     * QQ 官方开放平台绑定表（独立于 OneBot 的 qq_bindings）。
     * QQ官方平台下发的是 openid（与真实 QQ 号不同，与 OneBot 并非同一身份体系），
     * 必须使用独立的绑定表，避免 openid 与 QQ 号互相挤占唯一键。
     */
    public static String createQqOfficialBindingsTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return createQqOfficialBindingsTable("qq_official_bindings", pk, tsDefault, dialect);
    }

    /**
     * QQ 官方开放平台绑定表（可指定表名，供迁移重建时创建临时表）。
     *
     * <p>唯一键是 {@code (app_id, openid)} 与 {@code (app_id, user_id)}：openid 按 AppID 隔离，
     * 同一个真人在不同机器人（不同 AppID）下的 openid 不同，身份必须带 app_id 维度，
     * 否则会出现"在 A 机器人绑定、在 B 机器人显示未绑定"，且在 B 上重新绑定会因
     * user_id 已被占用而失败。</p>
     */
    public static String createQqOfficialBindingsTable(String tableName, String pk, String tsDefault,
                                                       DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                "id " + pk + ", " +
                "app_id VARCHAR(64) NOT NULL DEFAULT '', " +
                "openid VARCHAR(128) NOT NULL, " +
                "user_id BIGINT NOT NULL, " +
                "created_at " + tsDefault + ", " +
                (dialect.supportsForeignKeysInCreateTable()
                        ? "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                        : "") +
                "UNIQUE (app_id, openid), " +
                "UNIQUE (app_id, user_id)" +
                ")";
    }

    public static String createMcBindingsTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS mc_bindings (" +
                "id " + pk + ", " +
                "mc_uuid VARCHAR(64) NOT NULL UNIQUE, " +
                "mc_name VARCHAR(32) NOT NULL, " +
                "user_id BIGINT NOT NULL UNIQUE, " +
                "created_at " + tsDefault +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    public static String createRolesTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS roles (" +
                "id " + pk + ", " +
                "name VARCHAR(32) NOT NULL UNIQUE, " +
                "display_name VARCHAR(64) NOT NULL, " +
                "level INT NOT NULL, " +
                "created_at " + tsDefault +
                ")";
    }

    public static String createPermissionsTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS permissions (" +
                "id " + pk + ", " +
                "code VARCHAR(64) NOT NULL UNIQUE, " +
                "name VARCHAR(64) NOT NULL, " +
                "category VARCHAR(32) NOT NULL, " +
                "created_at " + tsDefault +
                ")";
    }

    public static String createRolePermissionsTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS role_permissions (" +
                "id " + pk + ", " +
                "role_name VARCHAR(32) NOT NULL, " +
                "permission_code VARCHAR(64) NOT NULL, " +
                "created_at " + tsDefault + ", " +
                "UNIQUE (role_name, permission_code)" +
                ")";
    }

    public static String createUserRolesTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS user_roles (" +
                "id " + pk + ", " +
                "user_id BIGINT NOT NULL, " +
                "role_name VARCHAR(32) NOT NULL, " +
                "created_at " + tsDefault + ", " +
                (dialect.supportsForeignKeysInCreateTable()
                        ? "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                        : "") +
                "UNIQUE (user_id, role_name)" +
                ")";
    }

    public static String createChatMessagesTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS chat_messages (" +
                "id " + pk + ", " +
                "channel VARCHAR(16) NOT NULL DEFAULT 'world', " +
                "sender_player_id BIGINT NOT NULL, " +
                "receiver_player_id BIGINT DEFAULT NULL, " +
                "content VARCHAR(1024) NOT NULL, " +
                "created_at " + tsDefault +
                (dialect.supportsIndexInCreateTable()
                        ? ", INDEX idx_chat_channel (channel), INDEX idx_chat_receiver (receiver_player_id)"
                        : "") +
                ")";
    }

    public static String createFriendsTable(String pk, String tsDefault, String tsUpdate,
                                            DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS friends (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "friend_player_id BIGINT NOT NULL, " +
                "status VARCHAR(16) NOT NULL DEFAULT 'pending', " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (dialect.supportsForeignKeysInCreateTable()
                        ? "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, FOREIGN KEY (friend_player_id) REFERENCES players(id) ON DELETE CASCADE, "
                        : "") +
                "UNIQUE (player_id, friend_player_id)" +
                ")";
    }

    public static String createPlayerActionLogsTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS player_action_logs (" +
                "id " + pk + ", " +
                "user_id BIGINT DEFAULT NULL, " +
                "player_name VARCHAR(64) DEFAULT '', " +
                "action VARCHAR(64) NOT NULL, " +
                "detail VARCHAR(1024) DEFAULT '', " +
                "qq_number VARCHAR(32) DEFAULT NULL, " +
                "created_at " + tsDefault +
                ")";
    }

    public static String createSectsTable(String pk, String tsDefault, String tsUpdate,
                                           DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS sects (" +
                "id " + pk + ", " +
                "name VARCHAR(32) NOT NULL UNIQUE, " +
                "description VARCHAR(256) DEFAULT '', " +
                "leader_player_id BIGINT NOT NULL, " +
                "level INT DEFAULT 1, " +
                "prestige BIGINT DEFAULT 0, " +
                "max_members INT DEFAULT 20, " +
                // ---- 宗主缺位继位 / 竞争期（B5）----
                // leader_absent_since：0 = 宗主在任；>0 = 自该毫秒时间戳起宗主缺位
                // （主动退位，或长期不活跃被判定弃坑），等待按贡献继位。
                "leader_absent_since BIGINT DEFAULT 0, " +
                // contest_until：0 = 无竞争期；>0 = 竞争期截止的毫秒时间戳。
                "contest_until BIGINT DEFAULT 0, " +
                // contest_challenger_id：竞争期内的挑战者；NULL/0 = 无。
                "contest_challenger_id BIGINT DEFAULT NULL, " +
                // contest_requested：挑战者已发起，等待宗主应战（0/1）。
                "contest_requested INT DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (leader_player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    public static String createSectMembersTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS sect_members (" +
                "id " + pk + ", " +
                "sect_id BIGINT NOT NULL, " +
                "player_id BIGINT NOT NULL, " +
                "role VARCHAR(16) NOT NULL DEFAULT 'MEMBER', " +
                "contribution BIGINT DEFAULT 0, " +
                "joined_at " + tsDefault +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (sect_id) REFERENCES sects(id) ON DELETE CASCADE, FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ", UNIQUE (player_id)" +
                ")";
    }

    public static String createSectApplicationsTable(String pk, String tsDefault, String tsUpdate,
                                                     DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS sect_applications (" +
                "id " + pk + ", " +
                "sect_id BIGINT NOT NULL, " +
                "player_id BIGINT NOT NULL, " +
                "message VARCHAR(256) DEFAULT '', " +
                "status VARCHAR(16) NOT NULL DEFAULT 'pending', " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (sect_id) REFERENCES sects(id) ON DELETE CASCADE, FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    public static String createSectWarehouseTable(String pk, String tsDefault, String tsUpdate,
                                                  DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS sect_warehouse (" +
                "id " + pk + ", " +
                "sect_id BIGINT NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "quantity INT NOT NULL DEFAULT 1, " +
                "donated_by_player_id BIGINT DEFAULT NULL, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (sect_id) REFERENCES sects(id) ON DELETE CASCADE"
                        : "") +
                ", UNIQUE (sect_id, item_key)" +
                ")";
    }

    public static String createSectWarsTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS sect_wars (" +
                "id " + pk + ", " +
                "attacker_sect_id BIGINT NOT NULL, " +
                "defender_sect_id BIGINT NOT NULL, " +
                "winner_sect_id BIGINT DEFAULT NULL, " +
                "attacker_wins INT DEFAULT 0, " +
                "defender_wins INT DEFAULT 0, " +
                "prestige_stake BIGINT DEFAULT 0, " +
                "battle_log TEXT, " +
                "created_at " + tsDefault +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (attacker_sect_id) REFERENCES sects(id) ON DELETE CASCADE, FOREIGN KEY (defender_sect_id) REFERENCES sects(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    /**
     * 地图地点表。
     *
     * <p>本表同时承载两类地点，用 {@code is_wilderness} 区分：
     * <ul>
     *   <li><b>已知世界</b>（{@code is_wilderness = 0}）：出厂手工编写的 15 个地点，id 1~15，
     *       {@code expires_at} 恒为 NULL，永不过期；</li>
     *   <li><b>野外</b>（{@code is_wilderness = 1}）：由地图文件驱动、程序生成的随机地点，
     *       id 从 {@code WildernessMapConfig.idBase}（默认 1000）起，带 {@code expires_at}，
     *       到期由刷新任务删除并重新生成，因此其资源是<b>随机且会变</b>的。</li>
     * </ul>
     * 两类共用本表而非各建一张，是为了让 {@code map_connections} 的外键、
     * 赶路的邻接判定、境界门槛、并发 CAS 全部原样复用 —— 野外地点对现有移动逻辑
     * 就是一个普通地点。</p>
     *
     * @param dangerLevel 危险度 0~4（0=安全 1=低危 2=中危 3=高危 4=极危），影响 PK 规则与产出
     * @param entryLocationId 野外节点的入口地点（已知世界地点 id）；已知世界地点为 NULL
     */
    public static String createMapLocationsTable(String pk, String tsDefault, String boolType) {
        return "CREATE TABLE IF NOT EXISTS map_locations (" +
                "id BIGINT PRIMARY KEY, " +
                "name VARCHAR(64) NOT NULL, " +
                "description TEXT, " +
                "region VARCHAR(32) DEFAULT '未知', " +
                "min_realm INT DEFAULT 0, " +
                "is_safe_zone " + boolType + ", " +
                "cultivation_bonus DOUBLE DEFAULT 1.0, " +
                "exploration_bonus DOUBLE DEFAULT 1.0, " +
                "danger_level INT DEFAULT 0, " +
                "is_wilderness INT DEFAULT 0, " +
                "expires_at BIGINT, " +
                "entry_location_id BIGINT, " +
                "created_at " + tsDefault + ")";
    }

    /**
     * 玩家探索记录：首次到达某地点时写一行，用于探索度与传送点解锁。
     * <p>只记"首次"，因此 {@code UNIQUE (player_id, location_id)}；重复到达不写。</p>
     */
    public static String createPlayerMapExplorationTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS player_map_exploration (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "location_id BIGINT NOT NULL, " +
                "first_visited_at BIGINT NOT NULL, " +
                "UNIQUE (player_id, location_id)" +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    /**
     * 世界 Boss 池（B6）。
     * <p>
     * 与「个人 PVE Boss」「团队 Raid Boss」的根本区别：<b>血量是全服共享的，而且直接落库</b>。
     * 没有任何内存态、没有定时器 —— 谁打掉多少就扣多少，重启后血条原样还在。
     * 到期（{@code expires_at}）由读取时的惰性重建处理，不依赖调度任务。</p>
     *
     * <p>{@code UNIQUE (scope, slot)} 是并发重建的护栏：两个请求同时发现池子过期时，
     * 后写入的那个会撞唯一键失败，捕获后重新读取即可，不会出现两套 Boss。</p>
     */
    public static String createWorldBossesTable(String pk, String tsDefault, String tsUpdate, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS world_bosses (" +
                "id " + pk + ", " +
                // scope：Boss 池的归属键。单世界服务器固定 "global"（全服共享同一批 Boss）。
                // 保留这一列是为了将来支持「按群/按分线」的世界 Boss，而不必改表。
                "scope VARCHAR(64) NOT NULL, " +
                // slot：池内序号 1..N，与 scope 组成唯一键。
                "slot INT NOT NULL, " +
                "name VARCHAR(64) NOT NULL, " +
                "description VARCHAR(256) DEFAULT '', " +
                "realm INT NOT NULL DEFAULT 0, " +
                // hp / max_hp：共享血量。hp 归零即被击杀，等到 expires_at 后随整池重建。
                "hp BIGINT NOT NULL DEFAULT 0, " +
                "max_hp BIGINT NOT NULL DEFAULT 0, " +
                "attack INT NOT NULL DEFAULT 0, " +
                "defense INT NOT NULL DEFAULT 0, " +
                "speed INT NOT NULL DEFAULT 0, " +
                // 灵石/修为总预算：按伤害比例分红，因此全服总发放天然不超过预算（见 WorldBossService）。
                "stone_budget BIGINT NOT NULL DEFAULT 0, " +
                "exp_budget BIGINT NOT NULL DEFAULT 0, " +
                "expires_at BIGINT NOT NULL DEFAULT 0, " +
                "killed_at BIGINT NOT NULL DEFAULT 0, " +
                "killer_player_id BIGINT DEFAULT NULL, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                "UNIQUE (scope, slot)" +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (killer_player_id) REFERENCES players(id) ON DELETE SET NULL"
                        : "") +
                ")";
    }

    /**
     * 世界 Boss 伤害台账（B6）。
     * <p>
     * 两个用途：一是排行榜展示（谁打了多少），二是<b>挑战冷却</b>的唯一依据
     * （{@code last_hit_at}）。后者很关键 —— 本项目没有体力系统，
     * 若不限次，一个高境界玩家可以连着把 Boss 一次性打死，其他人连参与的份都没有。
     * </p>
     */
    public static String createWorldBossDamageTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS world_boss_damage (" +
                "id " + pk + ", " +
                "boss_id BIGINT NOT NULL, " +
                "player_id BIGINT NOT NULL, " +
                "damage BIGINT NOT NULL DEFAULT 0, " +
                "hits INT NOT NULL DEFAULT 0, " +
                "last_hit_at BIGINT NOT NULL DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "UNIQUE (boss_id, player_id)" +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    /** 世界 Boss 按 (scope, 到期时间) 查询的索引：列表与惰性重建都走这个条件。 */
    public static String createWorldBossesScopeIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_world_bosses_scope_expires "
                + "ON world_bosses (scope, expires_at)";
    }

    /**
     * 野外资源点：野外地点上的可采集资源，带存量。
     * <p><b>已废弃</b>：野外改为文件存储（每个分片一个 JSON，
     * 见 {@code com.mtxgdn.game.map.WildernessChunkStore}），{@code DatabaseManager}
     * 已不再创建本表。保留定义是为了让老库的初始化逻辑可追溯 —— 已存在的库会留下一张空表，
     * 无害，且刻意不去 {@code DROP}（删库的风险远大于留一张空表）。</p>
     * <p>{@code remaining} 归零即采空，随野外地点的刷新周期一起重建 —— 这正是
     * "野外随机刷新资源"与已知世界固定倍率的区别所在。</p>
     */
    public static String createMapWildernessResourcesTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS map_wilderness_resources (" +
                "id " + pk + ", " +
                "location_id BIGINT NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "total_quantity INT NOT NULL DEFAULT 0, " +
                "remaining INT NOT NULL DEFAULT 0, " +
                "refreshed_at BIGINT NOT NULL DEFAULT 0, " +
                "created_at " + tsDefault +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (location_id) REFERENCES map_locations(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    /**
     * 地界 → 探索事件权重偏置表。
     * <p>让"落霞山产落霞草""万妖谷妖兽聚集"这类地点描述真正作用于事件抽取。</p>
     */
    public static String createMapLocationEventBiasTable(String pk, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS map_location_event_bias (" +
                "id " + pk + ", " +
                "location_id BIGINT NOT NULL, " +
                "event_key VARCHAR(64) NOT NULL, " +
                "weight_multiplier DOUBLE DEFAULT 1.0, " +
                "UNIQUE (location_id, event_key)" +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (location_id) REFERENCES map_locations(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    public static String createMapConnectionsTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS map_connections (" +
                "id " + pk + ", " +
                "from_location_id BIGINT NOT NULL, " +
                "to_location_id BIGINT NOT NULL, " +
                "travel_time_seconds INT DEFAULT 5, " +
                "created_at " + tsDefault +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (from_location_id) REFERENCES map_locations(id) ON DELETE CASCADE, FOREIGN KEY (to_location_id) REFERENCES map_locations(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    public static String createRedeemCodesTable(String pk, String tsDefault, String tsUpdate) {
        return "CREATE TABLE IF NOT EXISTS redeem_codes (" +
                "id " + pk + ", " +
                "code VARCHAR(32) NOT NULL UNIQUE, " +
                "name VARCHAR(64) DEFAULT '', " +
                "items_json TEXT, " +
                "gold BIGINT DEFAULT 0, " +
                "spirit_stones BIGINT DEFAULT 0, " +
                "exp BIGINT DEFAULT 0, " +
                "max_uses INT DEFAULT 1, " +
                "current_uses INT DEFAULT 0, " +
                "status VARCHAR(16) DEFAULT 'active', " +
                "expires_at TIMESTAMP NULL, " +
                "created_by VARCHAR(64) DEFAULT '', " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                ")";
    }

    public static String createRedeemedCodesTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS redeemed_codes (" +
                "id " + pk + ", " +
                "code_id BIGINT NOT NULL, " +
                "player_id BIGINT NOT NULL, " +
                "redeemed_at " + tsDefault +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (code_id) REFERENCES redeem_codes(id) ON DELETE CASCADE, FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ", UNIQUE (code_id, player_id)" +
                ")";
    }

    public static String createCavesTable(String pk, String tsDefault, String tsUpdate,
                                          DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS caves (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "name VARCHAR(32) NOT NULL DEFAULT '山间洞府', " +
                "level INT DEFAULT 1, " +
                "spirit_energy BIGINT DEFAULT 0, " +
                "max_spirit_energy INT DEFAULT 1000, " +
                "cultivation_bonus INT DEFAULT 5, " +
                "storage_bonus INT DEFAULT 10, " +
                "last_collect_time BIGINT DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ", UNIQUE (player_id)" +
                ")";
    }

    public static String createFormationsTable(String pk, String tsDefault, String tsUpdate,
                                               DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS formations (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "formation_key VARCHAR(32) NOT NULL, " +
                "name VARCHAR(32) NOT NULL, " +
                "level INT DEFAULT 1, " +
                "spirit_energy_boost INT DEFAULT 0, " +
                "cultivation_bonus INT DEFAULT 0, " +
                "defense_bonus INT DEFAULT 0, " +
                "heart_demon_resist INT DEFAULT 0, " +
                "duration_minutes INT DEFAULT 60, " +
                "placed_at BIGINT DEFAULT 0, " +
                "expires_at BIGINT DEFAULT 0, " +
                "active BOOLEAN DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    public static String createPlayerEconomyTable(String pk, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS player_economy (" +
                "player_id BIGINT PRIMARY KEY, " +
                "last_sign_in VARCHAR(16), " +
                "streak INT DEFAULT 0, " +
                "cultivation_boost_count INT DEFAULT 0, " +
                "total_stones_spent BIGINT DEFAULT 0, " +
                "total_stones_earned BIGINT DEFAULT 0" +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    public static String createPlayerBankTable(String pk, String tsDefault, String tsUpdate) {
        return "CREATE TABLE IF NOT EXISTS player_bank (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "deposit_type VARCHAR(16) NOT NULL DEFAULT 'current', " +
                "principal BIGINT NOT NULL DEFAULT 0, " +
                "interest_earned BIGINT NOT NULL DEFAULT 0, " +
                "rate DOUBLE NOT NULL, " +
                "deposited_at BIGINT NOT NULL, " +
                "matures_at BIGINT DEFAULT 0, " +
                "status VARCHAR(16) NOT NULL DEFAULT 'active', " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                ")";
    }

    public static String createAuctionListingsTable(String pk, String tsDefault, String tsUpdate) {
        return "CREATE TABLE IF NOT EXISTS auction_listings (" +
                "id " + pk + ", " +
                "seller_player_id BIGINT NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "quantity INT NOT NULL, " +
                "start_price BIGINT NOT NULL, " +
                "current_bid BIGINT DEFAULT NULL, " +
                "current_bidder_id BIGINT DEFAULT NULL, " +
                "fee_rate DOUBLE DEFAULT 0.07, " +
                "status VARCHAR(16) DEFAULT 'active', " +
                "end_time TIMESTAMP NOT NULL, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                ")";
    }

    public static String createAuctionBidsTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS auction_bids (" +
                "id " + pk + ", " +
                "listing_id BIGINT NOT NULL, " +
                "bidder_player_id BIGINT NOT NULL, " +
                "amount BIGINT NOT NULL, " +
                "created_at " + tsDefault +
                ")";
    }

    public static String createPlayerEnergyTable(String pk, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS player_energy (" +
                "player_id BIGINT PRIMARY KEY, " +
                "energy BIGINT DEFAULT 0" +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    public static String createPlayerTitlesTable(String pk, String tsDefault, String boolType,
                                                  DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS player_titles (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "title_key VARCHAR(64) NOT NULL, " +
                "is_equipped " + boolType + ", " +
                "acquired_at " + tsDefault + ", " +
                (dialect.supportsForeignKeysInCreateTable()
                        ? "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, "
                        : "") +
                "UNIQUE (player_id, title_key)" +
                ")";
    }

    public static String createPlayerBuffsTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS player_buffs (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "buff_id VARCHAR(64) NOT NULL, " +
                "attack_bonus INT DEFAULT 0, " +
                "defense_bonus INT DEFAULT 0, " +
                "speed_bonus INT DEFAULT 0, " +
                "spirit_bonus INT DEFAULT 0, " +
                "expire_time BIGINT NOT NULL, " +
                "created_at " + tsDefault +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    public static String createFarmPlotsTable(String pk, String tsDefault, DatabaseDialect dialect) {
        return "CREATE TABLE IF NOT EXISTS farm_plots (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "plot_index INT NOT NULL, " +
                "state VARCHAR(16) NOT NULL DEFAULT 'EMPTY', " +
                "seed_key VARCHAR(128), " +
                "crop_key VARCHAR(128), " +
                "planted_time BIGINT DEFAULT 0, " +
                "harvest_time BIGINT DEFAULT 0, " +
                "wilted_time BIGINT DEFAULT 0, " +
                "growth_stage INT DEFAULT 0, " +
                "water_level INT DEFAULT 100, " +
                "fertilizer_level INT DEFAULT 0, " +
                "yield INT DEFAULT 0, " +
                // 最后一次惰性结算时刻（毫秒）。水分衰减、病虫害检测都按 (now - settled_time)
                // 一次性折算，取代原先每秒推进的全服扫描。
                "settled_time BIGINT DEFAULT 0" +
                (dialect.supportsForeignKeysInCreateTable()
                        ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                        : "") +
                ")";
    }

    /**
     * V4 修复：JWT 吊销名单持久化表。
     * <p>
     * 原实现把已登出 token 只存在内存 {@code ConcurrentHashMap} 中，进程重启即清空，
     * 导致"已登出"的 7 天有效期 token 全部复活。落库后重启不再丢失吊销信息。
     * <p>
     * 只存 token 的 SHA-256 摘要（不存明文 token），避免数据库泄露后被直接冒用；
     * {@code expires_at} 用于定期清理过期条目，防止表无限增长。
     */
    public static String createTokenBlacklistTable(String pk, String tsDefault) {
        return "CREATE TABLE IF NOT EXISTS token_blacklist (" +
                "id " + pk + ", " +
                "token_hash VARCHAR(64) NOT NULL UNIQUE, " +
                "expires_at BIGINT NOT NULL, " +
                "created_at " + tsDefault +
                ")";
    }
}