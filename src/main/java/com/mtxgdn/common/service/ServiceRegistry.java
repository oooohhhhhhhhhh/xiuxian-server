package com.mtxgdn.common.service;

import com.mtxgdn.game.service.*;
import com.mtxgdn.minecraft.adapter.MinecraftPlayerBindingService;

public class ServiceRegistry {

    private static volatile PlayerService playerService;
    private static volatile RealmService realmService;
    private static volatile ItemService itemService;
    private static volatile SecretRealmService secretRealmService;
    private static volatile ExplorationService explorationService;
    private static volatile NewbieGuideService guideService;
    private static volatile SkillService skillService;
    private static volatile CombatService combatService;
    private static volatile DailyService dailyService;
    private static volatile TradeService tradeService;
    private static volatile HeartDemonService heartDemonService;
    private static volatile ChatService chatService;
    private static volatile FriendService friendService;
    private static volatile EnhanceService enhanceService;
    private static volatile CraftingService craftingService;
    private static volatile TechniqueService techniqueService;
    private static volatile SectService sectService;
    private static volatile EconomyService economyService;
    private static volatile EnergyService energyService;
    private static volatile TitleService titleService;
    private static volatile CaveService caveService;
    private static volatile FormationService formationService;
    private static volatile BuffService buffService;
    private static volatile FarmService farmService;
    private static volatile MapService mapService;
    private static volatile WorldBossService worldBossService;
    private static volatile DurabilityService durabilityService;
    private static volatile MinecraftPlayerBindingService mcBindingService;

    private ServiceRegistry() {
    }

    public static PlayerService getPlayerService() {
        if (playerService == null) {
            synchronized (ServiceRegistry.class) {
                if (playerService == null) {
                    playerService = new PlayerService();
                }
            }
        }
        return playerService;
    }

    public static RealmService getRealmService() {
        if (realmService == null) {
            synchronized (ServiceRegistry.class) {
                if (realmService == null) {
                    realmService = new RealmService(getPlayerService());
                }
            }
        }
        return realmService;
    }

    public static ItemService getItemService() {
        if (itemService == null) {
            synchronized (ServiceRegistry.class) {
                if (itemService == null) {
                    itemService = new ItemService();
                }
            }
        }
        return itemService;
    }

    public static SecretRealmService getSecretRealmService() {
        if (secretRealmService == null) {
            synchronized (ServiceRegistry.class) {
                if (secretRealmService == null) {
                    secretRealmService = new SecretRealmService(getPlayerService());
                }
            }
        }
        return secretRealmService;
    }

    public static ExplorationService getExplorationService() {
        if (explorationService == null) {
            synchronized (ServiceRegistry.class) {
                if (explorationService == null) {
                    explorationService = new ExplorationService(getPlayerService());
                }
            }
        }
        return explorationService;
    }

    public static NewbieGuideService getGuideService() {
        if (guideService == null) {
            synchronized (ServiceRegistry.class) {
                if (guideService == null) {
                    guideService = new NewbieGuideService();
                }
            }
        }
        return guideService;
    }

    public static SkillService getSkillService() {
        if (skillService == null) {
            synchronized (ServiceRegistry.class) {
                if (skillService == null) {
                    skillService = new SkillService();
                }
            }
        }
        return skillService;
    }

    public static CombatService getCombatService() {
        if (combatService == null) {
            synchronized (ServiceRegistry.class) {
                if (combatService == null) {
                    combatService = new CombatService();
                }
            }
        }
        return combatService;
    }

    public static DailyService getDailyService() {
        if (dailyService == null) {
            synchronized (ServiceRegistry.class) {
                if (dailyService == null) {
                    dailyService = new DailyService();
                }
            }
        }
        return dailyService;
    }

    public static TradeService getTradeService() {
        if (tradeService == null) {
            synchronized (ServiceRegistry.class) {
                if (tradeService == null) {
                    tradeService = new TradeService();
                }
            }
        }
        return tradeService;
    }

    public static HeartDemonService getHeartDemonService() {
        if (heartDemonService == null) {
            synchronized (ServiceRegistry.class) {
                if (heartDemonService == null) {
                    heartDemonService = new HeartDemonService();
                }
            }
        }
        return heartDemonService;
    }

    public static ChatService getChatService() {
        if (chatService == null) {
            synchronized (ServiceRegistry.class) {
                if (chatService == null) {
                    chatService = new ChatService();
                }
            }
        }
        return chatService;
    }

    public static FriendService getFriendService() {
        if (friendService == null) {
            synchronized (ServiceRegistry.class) {
                if (friendService == null) {
                    friendService = new FriendService();
                }
            }
        }
        return friendService;
    }

    public static EnhanceService getEnhanceService() {
        if (enhanceService == null) {
            synchronized (ServiceRegistry.class) {
                if (enhanceService == null) {
                    enhanceService = new EnhanceService();
                }
            }
        }
        return enhanceService;
    }

    public static CraftingService getCraftingService() {
        if (craftingService == null) {
            synchronized (ServiceRegistry.class) {
                if (craftingService == null) {
                    craftingService = new CraftingService();
                }
            }
        }
        return craftingService;
    }

    public static TechniqueService getTechniqueService() {
        if (techniqueService == null) {
            synchronized (ServiceRegistry.class) {
                if (techniqueService == null) {
                    techniqueService = new TechniqueService();
                }
            }
        }
        return techniqueService;
    }

    public static SectService getSectService() {
        if (sectService == null) {
            synchronized (ServiceRegistry.class) {
                if (sectService == null) {
                    sectService = new SectService();
                }
            }
        }
        return sectService;
    }

    public static EconomyService getEconomyService() {
        if (economyService == null) {
            synchronized (ServiceRegistry.class) {
                if (economyService == null) {
                    economyService = new EconomyService();
                }
            }
        }
        return economyService;
    }

    public static EnergyService getEnergyService() {
        if (energyService == null) {
            synchronized (ServiceRegistry.class) {
                if (energyService == null) {
                    energyService = new EnergyService();
                }
            }
        }
        return energyService;
    }

    public static TitleService getTitleService() {
        if (titleService == null) {
            synchronized (ServiceRegistry.class) {
                if (titleService == null) {
                    titleService = new TitleService();
                }
            }
        }
        return titleService;
    }

    public static CaveService getCaveService() {
        if (caveService == null) {
            synchronized (ServiceRegistry.class) {
                if (caveService == null) {
                    caveService = new CaveService();
                }
            }
        }
        return caveService;
    }

    public static FormationService getFormationService() {
        if (formationService == null) {
            synchronized (ServiceRegistry.class) {
                if (formationService == null) {
                    formationService = new FormationService();
                }
            }
        }
        return formationService;
    }

    public static BuffService getBuffService() {
        if (buffService == null) {
            synchronized (ServiceRegistry.class) {
                if (buffService == null) {
                    buffService = new BuffService();
                }
            }
        }
        return buffService;
    }

    public static FarmService getFarmService() {
        if (farmService == null) {
            synchronized (ServiceRegistry.class) {
                if (farmService == null) {
                    farmService = new FarmService();
                }
            }
        }
        return farmService;
    }

    public static MinecraftPlayerBindingService getMcBindingService() {
        if (mcBindingService == null) {
            synchronized (ServiceRegistry.class) {
                if (mcBindingService == null) {
                    mcBindingService = new MinecraftPlayerBindingService();
                }
            }
        }
        return mcBindingService;
    }

    public static MapService getMapService() {
        if (mapService == null) {
            synchronized (ServiceRegistry.class) {
                if (mapService == null) {
                    mapService = new MapService();
                }
            }
        }
        return mapService;
    }

    public static WorldBossService getWorldBossService() {
        if (worldBossService == null) {
            synchronized (ServiceRegistry.class) {
                if (worldBossService == null) {
                    worldBossService = new WorldBossService();
                }
            }
        }
        return worldBossService;
    }

    public static DurabilityService getDurabilityService() {
        if (durabilityService == null) {
            synchronized (ServiceRegistry.class) {
                if (durabilityService == null) {
                    durabilityService = new DurabilityService();
                }
            }
        }
        return durabilityService;
    }
}