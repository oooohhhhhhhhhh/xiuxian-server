package com.mtxgdn.client;

import com.mtxgdn.util.AppConfig;

public class ApiConfig {

    private static final String DEFAULT_SERVER_HOST = "127.0.0.1";
    private static final int DEFAULT_SERVER_PORT = 8080;

    private final String serverHost;
    private final int serverPort;
    private final String baseUrl;

    private static ApiConfig instance;

    private ApiConfig() {
        this.serverHost = AppConfig.get("server.host", DEFAULT_SERVER_HOST);
        this.serverPort = AppConfig.getInt("server.port", DEFAULT_SERVER_PORT);
        this.baseUrl = "http://" + serverHost + ":" + serverPort + "/api";
    }

    public static ApiConfig getInstance() {
        if (instance == null) {
            instance = new ApiConfig();
        }
        return instance;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public String getWebSocketUrl() {
        return "ws://" + serverHost + ":" + serverPort;
    }

    public void updateConfig(String host, int port) {
        try {
            var hostField = ApiConfig.class.getDeclaredField("serverHost");
            hostField.setAccessible(true);
            hostField.set(this, host);

            var portField = ApiConfig.class.getDeclaredField("serverPort");
            portField.setAccessible(true);
            portField.set(this, port);

            var urlField = ApiConfig.class.getDeclaredField("baseUrl");
            urlField.setAccessible(true);
            urlField.set(this, "http://" + host + ":" + port + "/api");
        } catch (Exception e) {
            throw new RuntimeException("更新配置失败", e);
        }
    }
}
