package com.mtxgdn.game.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.util.LangManager;

import java.util.List;
import java.util.Random;

public abstract class ExplorationEvent {

    private String fullKey;
    private String name;
    private String description;
    private int weight;

    protected ExplorationEvent(String namespace, String key, int weight) {
        this.fullKey = namespace + ":" + key;
        this.name = "explorationevent." + key + ".name";
        this.description = "explorationevent." + key + ".desc";
        this.weight = weight;
        ExplorationEventRegistry.register(this);
    }

    public String getFullKey() {
        return fullKey;
    }

    public String getNamespace() {
        int idx = fullKey.indexOf(':');
        return idx > 0 ? fullKey.substring(0, idx) : fullKey;
    }

    public String getKey() {
        int idx = fullKey.indexOf(':');
        return idx > 0 ? fullKey.substring(idx + 1) : fullKey;
    }

    public String getName() {
        return LangManager.get(name);
    }

    public String getDescription() {
        return LangManager.get(description);
    }

    public int getWeight() {
        return weight;
    }

    public abstract void execute(Player player, PlayerService playerService,
                                  ItemService itemService, Random random,
                                  ExplorationResult result, List<String> log);

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExplorationEvent other)) return false;
        return fullKey.equals(other.fullKey);
    }

    @Override
    public int hashCode() {
        return fullKey.hashCode();
    }

    @Override
    public String toString() {
        return fullKey;
    }
}
