package com.mtxgdn.adapter;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三方适配器 SPI 测试：工厂注册 → startOne 走 ManagedAdapter 分支 → stopOne → 注销。
 * 全程不涉及真实网络/套接字，仅验证 AdapterManager 的调度逻辑。
 */
class AdapterManagerFactoryTest {

    @Test
    void thirdPartyFactoryStartStopAndUnregister() {
        final AtomicBoolean started = new AtomicBoolean(false);
        final AtomicBoolean stopped = new AtomicBoolean(false);

        AdapterFactory factory = new AdapterFactory() {
            @Override
            public String getType() {
                return "spi-channel";
            }

            @Override
            public ManagedAdapter create(AdapterConfig config) {
                return new ManagedAdapter() {
                    @Override
                    public String getType() {
                        return "spi-channel";
                    }

                    @Override
                    public void start(AdapterConfig cfg) {
                        started.set(true);
                    }

                    @Override
                    public void stop() {
                        stopped.set(true);
                    }
                };
            }
        };

        try {
            // 未注册的类型：启动失败但不抛异常
            assertFalse(AdapterManager.startOne(
                    new AdapterConfig("unknown-1", "no-such-type", true, Map.of())));

            // 注册工厂后可正常启动
            assertTrue(AdapterManager.registerAdapterFactory(factory));
            AdapterConfig cfg = new AdapterConfig("spi-test", "spi-channel", true, Map.of("k", "v"));
            assertTrue(AdapterManager.startOne(cfg));
            assertTrue(started.get());
            assertTrue(AdapterManager.isRunning("spi-test"));

            // 重复启动同一实例被拒绝
            assertFalse(AdapterManager.startOne(cfg));

            // 同一类型重复注册被拒绝
            AdapterFactory another = new AdapterFactory() {
                @Override
                public String getType() {
                    return "spi-channel";
                }

                @Override
                public ManagedAdapter create(AdapterConfig config) {
                    return null;
                }
            };
            assertFalse(AdapterManager.registerAdapterFactory(another));

            // 内置类型不允许第三方注册
            AdapterFactory builtinDup = new AdapterFactory() {
                @Override
                public String getType() {
                    return "onebot";
                }

                @Override
                public ManagedAdapter create(AdapterConfig config) {
                    return null;
                }
            };
            assertFalse(AdapterManager.registerAdapterFactory(builtinDup));

            // 停止实例
            AdapterManager.stopOne("spi-test");
            assertTrue(stopped.get());
            assertFalse(AdapterManager.isRunning("spi-test"));

            // 注销后工厂不再可用
            AdapterManager.unregisterAdapterType("spi-channel");
            assertFalse(AdapterManager.startOne(
                    new AdapterConfig("spi-test-2", "spi-channel", true, Map.of())));
        } finally {
            AdapterManager.unregisterAdapterType("spi-channel");
            AdapterManager.stopAll();
        }
    }
}
