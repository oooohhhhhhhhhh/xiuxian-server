package com.mtxgdn.minecraft.adapter;

import com.mtxgdn.db.MinecraftPlayerBindingDAO;

public class MinecraftPlayerBindingService {

    private final MinecraftPlayerBindingDAO bindingDao = new MinecraftPlayerBindingDAO();

    public MinecraftPlayerBinding findByMcUuid(String mcUuid) {
        return bindingDao.findByMcUuid(mcUuid);
    }

    public MinecraftPlayerBinding findByMcName(String mcName) {
        return bindingDao.findByMcName(mcName);
    }

    public MinecraftPlayerBinding findByUserId(Long userId) {
        return bindingDao.findByUserId(userId);
    }

    public void bind(String mcUuid, String mcName, Long userId) {
        checkDuplicateBinding(mcUuid, userId);
        bindingDao.insertBinding(mcUuid, mcName, userId);
    }

    public void unbindByMcUuid(String mcUuid) {
        int affected = bindingDao.deleteByMcUuid(mcUuid);
        if (affected == 0) {
            throw new RuntimeException("该MC玩家未绑定任何用户");
        }
    }

    public void updateMcName(String mcUuid, String newName) {
        bindingDao.updateMcName(mcUuid, newName);
    }

    private void checkDuplicateBinding(String mcUuid, Long userId) {
        MinecraftPlayerBinding existingByUuid = findByMcUuid(mcUuid);
        if (existingByUuid != null) {
            throw new RuntimeException("该MC玩家已绑定用户: " + existingByUuid.getUserId());
        }
        MinecraftPlayerBinding existingByUser = findByUserId(userId);
        if (existingByUser != null) {
            throw new RuntimeException("该用户已绑定MC玩家: " + existingByUser.getMcName());
        }
    }
}
