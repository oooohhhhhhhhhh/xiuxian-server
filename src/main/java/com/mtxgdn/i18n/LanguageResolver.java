package com.mtxgdn.i18n;

import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;

/**
 * 按发送者/用户解析语言。
 *
 * <p>用于 REST 之外的通路：OneBot、QQ 官方、Minecraft 指令没有
 * {@code ContainerRequestContext}，语言必须由适配器显式解析后
 * 用 {@link I18nContext#set} 绑定。</p>
 *
 * <p>解析顺序：</p>
 * <ol>
 *   <li>玩家持久化偏好（{@code players.lang}）—— 用户显式设置过的语言</li>
 *   <li>服务端默认语言（{@code i18n.default-language}）</li>
 * </ol>
 *
 * <p>刻意<b>不</b>做"按平台猜语言"（例如 QQ 群默认中文）：那会在玩家手动
 * 切换语言后失效，且让行为变得难以预测。未绑定的访客统一用服务端默认语言，
 * 这是可解释的、也是玩家改偏好时唯一能覆盖的一层。</p>
 */
public final class LanguageResolver {

    private LanguageResolver() {
    }

    /** 按玩家 ID 解析语言。查不到玩家或无偏好时回落到服务端默认。 */
    public static GameLanguage forPlayer(long playerId) {
        PlayerInfo player = ServiceRegistry.getPlayerService().getPlayerInfoById(playerId);
        if (player == null) {
            return I18nManager.serverDefault();
        }
        return fromCode(player.getLanguage());
    }

    /** 按用户 ID 解析语言（先取该用户的角色，再取角色偏好）。 */
    public static GameLanguage forUser(long userId) {
        PlayerInfo player = ServiceRegistry.getPlayerService().getPlayerByUserId(userId);
        if (player == null) {
            return I18nManager.serverDefault();
        }
        return fromCode(player.getLanguage());
    }

    /**
     * 把存储的语言代码解析为枚举，解析失败时回落到服务端默认。
     *
     * <p>数据库里的值可能是历史脏数据（手改出来 {@code zh}、空串、null），
     * 一律安静回退而不是抛异常——语言值不该让一条指令挂掉。</p>
     */
    public static GameLanguage fromCode(String code) {
        GameLanguage parsed = GameLanguage.parse(code);
        return parsed != null ? parsed : I18nManager.serverDefault();
    }
}
