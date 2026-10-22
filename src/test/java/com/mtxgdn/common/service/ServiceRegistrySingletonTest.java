package com.mtxgdn.common.service;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护「有状态服务必须是单例」这一前提。
 *
 * <p><b>为什么需要这个测试</b>：项目里同时存在两条获取服务的路径 ——
 * {@link ServiceRegistry#getCombatService()} 和 {@code new CombatService()}。
 * 对无状态服务这只是浪费对象；但对把状态放在<b>实例字段</b>里的服务，两条路径
 * 等于两套互不可见的状态：决斗挑战写进 A 实例的 {@code pendingChallenges}，
 * 接受挑战却去 B 实例查，结果永远是"没有待接受的挑战"。更糟的是不同通道
 * （REST / WebSocket / QQ / Minecraft）各拿各的实例 —— 玩家在 QQ 上发起挑战、
 * 在网页上接受就必然失败。</p>
 *
 * <p>本测试把两件事钉住：</p>
 * <ol>
 *   <li>注册表对同一服务永远返回同一个对象；</li>
 *   <li>源码里不再出现对这四个有状态服务的直接构造 —— 缺陷的形态就是
 *       "有人写了 {@code new XxxService()}"，扫描能精确地在引入的那一刻失败。</li>
 * </ol>
 */
class ServiceRegistrySingletonTest extends TestBase {

    /** 状态放在实例字段里、因此必须全局唯一的服务。 */
    private static final String[] STATEFUL_SERVICES = {
            "CombatService",  // pendingChallenges：决斗挑战只存在于内存
            "MapService",     // locationCache / connections / travelTimes / eventBias / wildernessCache / initialized
            "DailyService"    // todayPhenomenon / phenomenonDate：每日天象
    };

    @Test
    @DisplayName("注册表对同一服务返回同一实例")
    void registryReturnsSameInstance() {
        assertSame(ServiceRegistry.getCombatService(), ServiceRegistry.getCombatService(),
                "CombatService 持有 pendingChallenges 实例状态，重复创建会让挑战凭空消失");
        assertSame(ServiceRegistry.getPlayerService(), ServiceRegistry.getPlayerService());
        assertSame(ServiceRegistry.getMapService(), ServiceRegistry.getMapService(),
                "MapService 有多份实例缓存，重复创建会导致缓存与库不一致");
        assertSame(ServiceRegistry.getDailyService(), ServiceRegistry.getDailyService());
        assertSame(ServiceRegistry.getOfflineRewardService(), ServiceRegistry.getOfflineRewardService());
    }

    @Test
    @DisplayName("有状态服务不得被直接 new：状态必须只有一份")
    void statefulServicesAreNeverInstantiatedDirectly() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("ServiceRegistry.java") || name.equals("TeamService.java")) {
                    continue; // 注册表本身与单例自身的构造点是合法的
                }
                String source = Files.readString(file);
                for (String service : STATEFUL_SERVICES) {
                    if (source.contains("new " + service + "()")) {
                        offenders.add(file + " -> new " + service + "()");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "有状态服务必须统一从 ServiceRegistry 获取，否则不同通道各拿一份实例、"
                        + "状态互不可见（决斗挑战 / 地图缓存 / 每日天象会凭空丢失）：\n"
                        + String.join("\n", offenders));
    }
}
