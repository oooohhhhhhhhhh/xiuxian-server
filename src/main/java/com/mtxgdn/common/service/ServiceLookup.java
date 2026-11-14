package com.mtxgdn.common.service;

import com.mtxgdn.game.collection.CollectionService;
import com.mtxgdn.game.service.*;
import com.mtxgdn.minecraft.adapter.MinecraftPlayerBindingService;

/**
 * 服务查询面（只读）。
 *
 * <p>由 {@link ServiceContainer} 实现。业务类（服务、指令、REST 资源、WebSocket 处理器、
 * 适配器）通过<b>构造参数</b>拿到它，而不是调用 {@code ServiceRegistry} 的静态方法：
 * 静态方法把"当前是哪套服务实例"藏进全局状态，测试之间互相污染，依赖关系也看不出来。
 * </p>
 *
 * <p><b>为什么查询面而不是每个服务各自声明构造参数</b>：服务之间互相引用成环
 * （{@code CaveService} 与 {@code FormationService} 是文档里点名的真环，
 * {@code PlayerService} 又是几乎所有服务的上游）。逐个传参会把构造顺序变成
 * 一道必须手工拓扑排序的题；传查询面则让"用的时候再取"成为默认，环自然消失。
 * 需要的具体服务在类里声明为 {@code private final XxxService} 并只在构造期取一次，
 * 依赖仍然一目了然。</p>
 */
public interface ServiceLookup {

    PlayerService getPlayerService();
    RealmService getRealmService();
    ItemService getItemService();
    SecretRealmService getSecretRealmService();
    ExplorationService getExplorationService();
    NewbieGuideService getGuideService();
    SkillService getSkillService();
    CombatService getCombatService();
    DailyService getDailyService();
    TradeService getTradeService();
    HeartDemonService getHeartDemonService();
    ChatService getChatService();
    FriendService getFriendService();
    EnhanceService getEnhanceService();
    CraftingService getCraftingService();
    TechniqueService getTechniqueService();
    SectService getSectService();
    EconomyService getEconomyService();
    EnergyService getEnergyService();
    TitleService getTitleService();
    CaveService getCaveService();
    FormationService getFormationService();
    BuffService getBuffService();
    FarmService getFarmService();
    MinecraftPlayerBindingService getMcBindingService();
    MapService getMapService();
    WorldBossService getWorldBossService();
    DurabilityService getDurabilityService();
    OfflineRewardService getOfflineRewardService();
    RedeemCodeService getRedeemCodeService();
    CollectionService getCollectionService();
}
