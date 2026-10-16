package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemServiceTest extends TestBase {

    private ItemService itemService;

    @BeforeEach
    void setUp() {
        itemService = new ItemService();
    }

    @Test
    @DisplayName("validateAddParams should throw exception for negative quantity")
    void testValidateAddParamsNegativeQuantity() {
        assertThrows(IllegalArgumentException.class, () -> {
            itemService.addItem(1L, "test_item", -1);
        });
    }

    @Test
    @DisplayName("validateAddParams should throw exception for zero quantity")
    void testValidateAddParamsZeroQuantity() {
        assertThrows(IllegalArgumentException.class, () -> {
            itemService.addItem(1L, "test_item", 0);
        });
    }

    @Test
    @DisplayName("validateRemoveParams should throw exception for negative quantity")
    void testValidateRemoveParamsNegativeQuantity() {
        assertThrows(IllegalArgumentException.class, () -> {
            itemService.removeItem(1L, "test_item", -1);
        });
    }

    @Test
    @DisplayName("validateRemoveParams should throw exception for zero quantity")
    void testValidateRemoveParamsZeroQuantity() {
        assertThrows(IllegalArgumentException.class, () -> {
            itemService.removeItem(1L, "test_item", 0);
        });
    }

    @Test
    @DisplayName("getInventoryCapacity should return base capacity + level bonus")
    void testGetInventoryCapacity() {
        int capacity = itemService.getInventoryCapacity(1L);
        assertTrue(capacity >= 30);
    }
}