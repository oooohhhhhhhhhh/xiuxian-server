package com.mtxgdn.common.service;

import com.mtxgdn.game.service.*;

public class ServiceRegistry {

    private static final PlayerService playerService = new PlayerService();
    private static final RealmService realmService = new RealmService(playerService);
    private static final ItemService itemService = new ItemService();
    private static final SecretRealmService secretRealmService = new SecretRealmService(playerService);
    private static final ExplorationService explorationService = new ExplorationService(playerService);
    private static final NewbieGuideService guideService = new NewbieGuideService();
    private static final SkillService skillService = new SkillService();
    private static final CombatService combatService = new CombatService();
    private static final DailyService dailyService = new DailyService();
    private static final TradeService tradeService = new TradeService();
    private static final HeartDemonService heartDemonService = new HeartDemonService();
    private static final ChatService chatService = new ChatService();
    private static final FriendService friendService = new FriendService();
    private static final EnhanceService enhanceService = new EnhanceService();
    private static final CraftingService craftingService = new CraftingService();
    private static final TechniqueService techniqueService = new TechniqueService();
    private static final SectService sectService = new SectService();
    private static final EconomyService economyService = new EconomyService();
    private static final EnergyService energyService = new EnergyService();
    private static final TitleService titleService = new TitleService();
    private static CaveService caveService;
    private static FormationService formationService;

    static {
        caveService = new CaveService();
        formationService = new FormationService();
    }

    private ServiceRegistry() {
    }

    public static PlayerService getPlayerService() {
        return playerService;
    }

    public static RealmService getRealmService() {
        return realmService;
    }

    public static ItemService getItemService() {
        return itemService;
    }

    public static SecretRealmService getSecretRealmService() {
        return secretRealmService;
    }

    public static ExplorationService getExplorationService() {
        return explorationService;
    }

    public static NewbieGuideService getGuideService() {
        return guideService;
    }

    public static SkillService getSkillService() {
        return skillService;
    }

    public static CombatService getCombatService() {
        return combatService;
    }

    public static DailyService getDailyService() {
        return dailyService;
    }

    public static TradeService getTradeService() {
        return tradeService;
    }

    public static HeartDemonService getHeartDemonService() {
        return heartDemonService;
    }

    public static ChatService getChatService() {
        return chatService;
    }

    public static FriendService getFriendService() {
        return friendService;
    }

    public static EnhanceService getEnhanceService() {
        return enhanceService;
    }

    public static CraftingService getCraftingService() {
        return craftingService;
    }

    public static TechniqueService getTechniqueService() {
        return techniqueService;
    }

    public static SectService getSectService() {
        return sectService;
    }

    public static EconomyService getEconomyService() {
        return economyService;
    }

    public static EnergyService getEnergyService() {
        return energyService;
    }

    public static TitleService getTitleService() {
        return titleService;
    }

    public static CaveService getCaveService() {
        return caveService;
    }

    public static FormationService getFormationService() {
        return formationService;
    }
}
