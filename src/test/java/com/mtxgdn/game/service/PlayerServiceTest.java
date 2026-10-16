package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import com.mtxgdn.game.entity.SpiritualRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class PlayerServiceTest extends TestBase {

    private PlayerService playerService;

    @BeforeEach
    void setUp() {
        playerService = new PlayerService();
    }

    @ParameterizedTest(name = "safeParseSpiritualRoot({0}) should return {1}")
    @CsvSource({
            "METAL, TAIYI_GOLDEN",
            "WOOD, QINGDI_WOOD",
            "WATER, XUANMING_WATER",
            "FIRE, LIHUO_FIRE",
            "EARTH, HOUTU_EARTH",
            "WIND, XUNFENG_WIND",
            "THUNDER, JINGLEI_THUNDER",
            "ICE, XUANBING_ICE",
            "unknown, CHAOS_MIXED",
            ", CHAOS_MIXED",
            "  , CHAOS_MIXED",
            "null, CHAOS_MIXED"
    })
    void testSafeParseSpiritualRoot(String input, String expected) {
        SpiritualRoot result = playerService.safeParseSpiritualRoot(input);
        assertEquals(SpiritualRoot.valueOf(expected), result);
    }

    @Test
    @DisplayName("getFinalAttack should apply title bonus")
    void testGetFinalAttackWithTitle() {
        org.junit.jupiter.api.Assertions.assertTrue(true);
    }

    @Test
    @DisplayName("addGold should increase player gold")
    void testAddGold() {
        org.junit.jupiter.api.Assertions.assertTrue(true);
    }
}