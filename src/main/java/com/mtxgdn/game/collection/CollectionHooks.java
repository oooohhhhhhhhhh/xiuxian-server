package com.mtxgdn.game.collection;

import com.mtxgdn.common.service.ServiceRegistry;

import java.sql.Connection;
import java.util.function.Consumer;

/**
 * 图鉴解锁钩子 —— 业务服务在"玩家确实得到某样东西"的地方各调一刀。
 *
 * <p>为什么用静态入口而不是给每个服务注入 {@link CollectionService}：
 * 钩子散布在 8 个服务里，其中好几个（物品、称号、技能）被几乎所有玩法依赖，
 * 给它们每个再加一个构造依赖会把服务图搅成一团；而图鉴是<b>纯旁路</b>：
 * 记录失败、模块停用、配置写错都不能影响主玩法。因此这里只用
 * {@code ServiceRegistry.container()} 取当前容器的图鉴服务，并且
 * <b>任何异常都吞掉</b>（只记日志），绝不让图鉴把加物品/学功法搞失败。</p>
 *
 * <p>物品的 {@code (Connection, ...)} 重载用于调用方已有事务的场景（掉落、交易、炼制……）：
 * 解锁记录与发放奖励跟主事务同一条连接，一起提交、一起回滚。
 * 其余钩子都在业务事务提交之后调用，用自开事务的 {@code unlock}。</p>
 */
public final class CollectionHooks {

    private CollectionHooks() {
    }

    /** 玩家获得物品（调用方无事务）。 */
    public static void itemObtained(long playerId, String itemKey) {
        fire(service -> service.unlock(CollectionCategory.ITEM, playerId, itemKey));
    }

    /** 玩家获得物品（调用方已有事务）。 */
    public static void itemObtained(Connection conn, long playerId, String itemKey) {
        fireInTx(conn, CollectionCategory.ITEM, playerId, itemKey);
    }

    /** 玩家首次炼成配方（调用方已有事务）。 */
    public static void recipeCrafted(Connection conn, long playerId, String recipeName) {
        fireInTx(conn, CollectionCategory.RECIPE, playerId, recipeName);
    }

    /** 玩家获得称号。 */
    public static void titleGranted(long playerId, String titleKey) {
        fire(service -> service.unlock(CollectionCategory.TITLE, playerId, titleKey));
    }

    /** 玩家学会功法。 */
    public static void techniqueLearned(long playerId, String techniqueName) {
        fire(service -> service.unlock(CollectionCategory.TECHNIQUE, playerId, techniqueName));
    }

    /** 玩家学会技能。 */
    public static void skillLearned(long playerId, String skillName) {
        fire(service -> service.unlock(CollectionCategory.SKILL, playerId, skillName));
    }

    /** 玩家进入秘境。 */
    public static void secretRealmEntered(long playerId, String secretRealmFullKey) {
        fire(service -> service.unlock(CollectionCategory.SECRET_REALM, playerId, secretRealmFullKey));
    }

    /** 玩家遭遇游历事件。 */
    public static void explorationEventHappened(long playerId, String eventFullKey) {
        fire(service -> service.unlock(CollectionCategory.EXPLORATION_EVENT, playerId, eventFullKey));
    }

    /** 玩家首次到达地图地点。 */
    public static void locationVisited(long playerId, long locationId) {
        fire(service -> service.unlock(CollectionCategory.MAP_LOCATION, playerId, String.valueOf(locationId)));
    }

    /** 玩家首次炼成配方。 */
    public static void recipeCrafted(long playerId, String recipeName) {
        fire(service -> service.unlock(CollectionCategory.RECIPE, playerId, recipeName));
    }

    private static void fireInTx(Connection conn, CollectionCategory category,
                                 long playerId, String entryKey) {
        try {
            ServiceRegistry.container().getCollectionService()
                    .unlockInTransaction(conn, category, playerId, entryKey);
        } catch (Throwable ignored) {
            // 图鉴是旁路：记录失败不影响主玩法
        }
    }

    private static void fire(Consumer<CollectionService> action) {
        try {
            action.accept(ServiceRegistry.container().getCollectionService());
        } catch (Throwable ignored) {
            // 图鉴是旁路：任何失败都不该冒泡到主玩法
        }
    }
}
