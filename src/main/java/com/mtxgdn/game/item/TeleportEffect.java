package com.mtxgdn.game.item;

import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.MapService;
import com.mtxgdn.game.service.PlayerService;

import java.util.Map;

/**
 * 传送符效果：无视路径直接传送到指定地点（保留境界校验）。
 *
 * <p>目的地由物品在注册时声明（{@link #to(String)}），此前该参数被直接丢弃，
 * 无论配置成什么都会传到太虚城 —— 现已真正生效。</p>
 */
public class TeleportEffect extends ItemEffect {

    /** 默认目的地：太虚城（中原第一大城，安全区）。 */
    private static final long DEFAULT_LOCATION_ID = 4L;

    /** 兼容旧配置里的口语化叫法。 */
    private static final Map<String, String> DESTINATION_ALIASES = Map.of(
            "主城", "太虚城",
            "main_city", "太虚城",
            "主城/太虚城", "太虚城"
    );

    private final String destination;

    public TeleportEffect() {
        this(null);
    }

    private TeleportEffect(String destination) {
        this.destination = destination;
    }

    /**
     * 指定目的地的传送符效果。
     *
     * @param destination 地点名（支持"主城"等别名）；为空或无法解析时回落到太虚城
     */
    public static TeleportEffect to(String destination) {
        return new TeleportEffect(destination);
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        MapService mapService = ServiceRegistry.getMapService();
        MapLocation target = resolveDestination(mapService);
        if (target == null) {
            return "传送失败：目的地【" + destination + "】不存在";
        }

        Map<String, Object> result = mapService.teleportToLocation(playerId, target.getId());
        boolean ok = result != null && Boolean.TRUE.equals(result.get("success"));
        return ok ? "传送成功：" + result.get("message")
                : "传送失败：" + (result != null ? result.get("message") : "未知错误");
    }

    /** 按名字解析目的地（先别名归一），解析不到则回落到主城。 */
    private MapLocation resolveDestination(MapService mapService) {
        if (destination != null && !destination.isBlank()) {
            String trimmed = destination.trim();
            String name = DESTINATION_ALIASES.getOrDefault(trimmed, trimmed);
            MapLocation loc = mapService.getLocationByName(name);
            if (loc != null) {
                return loc;
            }
        }
        return mapService.getLocation(DEFAULT_LOCATION_ID);
    }
}
