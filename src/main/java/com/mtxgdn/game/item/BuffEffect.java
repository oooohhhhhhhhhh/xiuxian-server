package com.mtxgdn.game.item;

import com.mtxgdn.game.service.BuffService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.sql.Connection;
import java.sql.SQLException;

public class BuffEffect extends ItemEffect {

    private int attackBonus;
    private int defenseBonus;
    private int speedBonus;
    private int spiritBonus;
    private int durationSeconds;

    public BuffEffect() {
    }

    public BuffEffect(int attackBonus, int defenseBonus, int speedBonus, int spiritBonus, int durationSeconds) {
        this.attackBonus = attackBonus;
        this.defenseBonus = defenseBonus;
        this.speedBonus = speedBonus;
        this.spiritBonus = spiritBonus;
        this.durationSeconds = durationSeconds;
    }

    public int getAttackBonus() {
        return attackBonus;
    }

    public void setAttackBonus(int attackBonus) {
        this.attackBonus = attackBonus;
    }

    public int getDefenseBonus() {
        return defenseBonus;
    }

    public void setDefenseBonus(int defenseBonus) {
        this.defenseBonus = defenseBonus;
    }

    public int getSpeedBonus() {
        return speedBonus;
    }

    public void setSpeedBonus(int speedBonus) {
        this.speedBonus = speedBonus;
    }

    public int getSpiritBonus() {
        return spiritBonus;
    }

    public void setSpiritBonus(int spiritBonus) {
        this.spiritBonus = spiritBonus;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    @Override
    public boolean isInstantEffect() {
        return durationSeconds <= 0;
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        StringBuilder sb = new StringBuilder();

        if (durationSeconds > 0) {
            BuffService buffService = new BuffService();
            buffService.addBuff(playerId, "buff_" + System.currentTimeMillis(),
                attackBonus, defenseBonus, speedBonus, spiritBonus, durationSeconds);

            if (attackBonus > 0) sb.append("攻击力 +").append(attackBonus).append("，");
            if (defenseBonus > 0) sb.append("防御力 +").append(defenseBonus).append("，");
            if (speedBonus > 0) sb.append("速度 +").append(speedBonus).append("，");
            if (spiritBonus > 0) sb.append("灵力 +").append(spiritBonus).append("，");
            if (sb.length() > 0) {
                sb.append("持续 ").append(durationSeconds).append(" 秒");
            }
        } else {
            if (attackBonus > 0) {
                playerService.addAttack(playerId, attackBonus);
                sb.append("攻击力 +").append(attackBonus).append("，");
            }
            if (defenseBonus > 0) {
                playerService.addDefense(playerId, defenseBonus);
                sb.append("防御力 +").append(defenseBonus).append("，");
            }
            if (speedBonus > 0) {
                playerService.addSpeed(playerId, speedBonus);
                sb.append("速度 +").append(speedBonus).append("，");
            }
            if (spiritBonus > 0) {
                playerService.addSpirit(playerId, spiritBonus);
                sb.append("灵力 +").append(spiritBonus).append("，");
            }
        }

        return sb.toString();
    }

    public String execute(Connection conn, long playerId, PlayerService playerService, ItemService itemService) throws SQLException {
        StringBuilder sb = new StringBuilder();

        if (durationSeconds > 0) {
            BuffService buffService = new BuffService();
            buffService.addBuff(playerId, "buff_" + System.currentTimeMillis(),
                attackBonus, defenseBonus, speedBonus, spiritBonus, durationSeconds);

            if (attackBonus > 0) sb.append("攻击力 +").append(attackBonus).append("，");
            if (defenseBonus > 0) sb.append("防御力 +").append(defenseBonus).append("，");
            if (speedBonus > 0) sb.append("速度 +").append(speedBonus).append("，");
            if (spiritBonus > 0) sb.append("灵力 +").append(spiritBonus).append("，");
            if (sb.length() > 0) {
                sb.append("持续 ").append(durationSeconds).append(" 秒");
            }
        } else {
            if (attackBonus > 0) {
                playerService.addAttack(conn, playerId, attackBonus);
                sb.append("攻击力 +").append(attackBonus).append("，");
            }
            if (defenseBonus > 0) {
                playerService.addDefense(conn, playerId, defenseBonus);
                sb.append("防御力 +").append(defenseBonus).append("，");
            }
            if (speedBonus > 0) {
                playerService.addSpeed(conn, playerId, speedBonus);
                sb.append("速度 +").append(speedBonus).append("，");
            }
            if (spiritBonus > 0) {
                playerService.addSpirit(conn, playerId, spiritBonus);
                sb.append("灵力 +").append(spiritBonus).append("，");
            }
        }

        return sb.toString();
    }

    public static BuffEffect of(int attack, int defense, int speed, int spirit, int durationSeconds) {
        return new BuffEffect(attack, defense, speed, spirit, durationSeconds);
    }
}
