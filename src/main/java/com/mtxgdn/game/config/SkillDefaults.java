package com.mtxgdn.game.config;

import java.util.Arrays;
import java.util.List;

/**
 * 出厂技能数据（{@code skills} 表的初始内容）。
 *
 * <p>从 {@code SkillService.insertDefaultSkills()} 搬出来的纯数据。
 * 搬出来的理由和 {@link TechniqueDefaults} 一样：服务类里塞 16 段
 * 几百字符的 SQL 字面量，读代码时要在数据和逻辑之间来回翻；
 * 现在调平衡性（改伤害、改耗蓝、改冷却）只需要动这一个文件。</p>
 *
 * <p><b>数值一个都没改</b>，逐条搬自原实现，包括每条 INSERT 的列顺序
 * 与字面量文本。</p>
 *
 * <p>每条都是完整的 {@code INSERT INTO skills ... VALUES ...} 语句，
 * 用 {@code Statement.executeUpdate(String)} 执行：原实现就是这么做的，
 * 而且重复初始化时 id 主键冲突会被逐条 catch 跳过，所以这些语句必须
 * 自带显式的 {@code id} 才能保留这个幂等语义。</p>
 */
public final class SkillDefaults {

    /** 出厂技能 SQL，顺序即入库顺序。 */
    public static final List<String> INSERT_STATEMENTS = Arrays.asList(
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (1, '烈火掌', '以灵力催动烈焰，掌风如火', 1, 100, 50, 30, 10, 3, 0, 0, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (2, '玄冰刺', '凝聚寒气成冰刺，锐不可当', 2, 500, 200, 60, 20, 5, 0, 0, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (3, '天雷咒', '引九天神雷，威势惊人', 3, 2000, 500, 100, 35, 8, 0, 0, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (4, '万剑诀', '剑气万千，遮天蔽日', 4, 5000, 1000, 180, 50, 10, 0, 0, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (5, '回春术', '以灵力治愈自身伤势', 0, 200, 100, 0, 15, 10, 1, 60, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (6, '甘霖普降', '天降甘霖，大量恢复生命', 2, 800, 300, 0, 30, 15, 1, 150, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (7, '风驰电掣', '身法如电，闪避致命一击', 1, 300, 150, 0, 10, 20, 2, 0, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (8, '金刚护体', '灵气化盾，坚如金刚', 2, 600, 250, 0, 20, 30, 2, 0, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (9, '基础剑诀', '记载了基础剑法的口诀，适合初学者练习', 0, 50, 20, 20, 8, 2, 0, 0, 10)",
            // ===== 高境界技能（补全合体~真仙的内容天花板） =====
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (10, '紫霄神雷', '引紫霄天雷淬敌，神威莫测', 6, 20000, 8000, 300, 80, 12, 0, 0, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (11, '焚天圣火', '圣火焚天，万物成灰', 7, 50000, 20000, 450, 120, 15, 0, 0, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (12, '混沌剑气', '混沌初开第一剑，斩尽诸天', 8, 120000, 50000, 650, 180, 18, 0, 0, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (13, '大道归一', '万法归宗，一指点碎虚空', 9, 300000, 150000, 900, 250, 20, 0, 0, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (14, '天元回春', '以天元之力重塑肉身，起死回生', 6, 30000, 10000, 0, 60, 12, 1, 800, 10)",
            "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (15, '万象归元', '万象归一，生机无限', 7, 80000, 30000, 0, 90, 15, 1, 2000, 10)"
    );

    private SkillDefaults() {
    }
}
