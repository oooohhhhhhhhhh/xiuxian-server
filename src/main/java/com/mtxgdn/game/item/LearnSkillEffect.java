package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.SkillService;

public class LearnSkillEffect extends ItemEffect {

    private long skillId;

    public LearnSkillEffect() {
    }

    public LearnSkillEffect(long skillId) {
        this.skillId = skillId;
    }

    public long getSkillId() {
        return skillId;
    }

    public void setSkillId(long skillId) {
        this.skillId = skillId;
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        SkillService skillService = new SkillService();
        var result = skillService.learnSkillFromBook(playerId, skillId);
        return (String) result.getOrDefault("message", "");
    }

    public static LearnSkillEffect of(long skillId) {
        return new LearnSkillEffect(skillId);
    }
}
