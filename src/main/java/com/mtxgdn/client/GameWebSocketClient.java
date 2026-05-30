package com.mtxgdn.client;

import com.google.gson.JsonObject;
import com.mtxgdn.common.GameMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class GameWebSocketClient {

    private final ApiConfig config;
    private WebSocket webSocket;
    private final AtomicLong msgIdCounter = new AtomicLong(0);
    private final CopyOnWriteArrayList<Consumer<GameMessage>> messageListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> onConnectedListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> onErrorListeners = new CopyOnWriteArrayList<>();
    private volatile boolean connected = false;
    private volatile boolean authenticated = false;
    private String pendingAuthToken;

    public GameWebSocketClient() {
        this.config = ApiConfig.getInstance();
    }

    public void connect(String authToken) {
        if (connected) {
            return;
        }

        this.pendingAuthToken = authToken;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(config.getWebSocketUrl()), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        WebSocket.Listener.super.onOpen(webSocket);
                        connected = true;

                        JsonObject authData = new JsonObject();
                        authData.addProperty("token", pendingAuthToken);
                        GameMessage authMessage = GameMessage.create(nextMsgId(), "auth", authData);
                        webSocket.sendText(authMessage.toJson(), true);

                        for (Runnable listener : onConnectedListeners) {
                            try {
                                listener.run();
                            } catch (Exception ignored) {
                            }
                        }
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            String text = buffer.toString();
                            buffer.setLength(0);
                            try {
                                GameMessage msg = GameMessage.fromJson(text);
                                if ("welcome".equals(msg.getType())) {
                                    authenticated = true;
                                }
                                for (Consumer<GameMessage> listener : messageListeners) {
                                    try {
                                        listener.accept(msg);
                                    } catch (Exception ignored) {
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        connected = false;
                        authenticated = false;
                        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        connected = false;
                        authenticated = false;
                        for (Consumer<String> listener : onErrorListeners) {
                            try {
                                listener.accept(error.getMessage());
                            } catch (Exception ignored) {
                            }
                        }
                        WebSocket.Listener.super.onError(webSocket, error);
                    }
                });

        future.thenAccept(ws -> this.webSocket = ws);
    }

    public long nextMsgId() {
        return msgIdCounter.incrementAndGet();
    }

    public void send(GameMessage message) {
        if (webSocket != null && connected && authenticated) {
            webSocket.sendText(message.toJson(), true);
        }
    }

    public void send(String type, JsonObject data) {
        send(GameMessage.create(nextMsgId(), type, data));
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
            connected = false;
            authenticated = false;
        }
    }

    public boolean isConnected() {
        return connected && authenticated;
    }

    public void addMessageListener(Consumer<GameMessage> listener) {
        messageListeners.add(listener);
    }

    public void removeMessageListener(Consumer<GameMessage> listener) {
        messageListeners.remove(listener);
    }

    public void addOnConnectedListener(Runnable listener) {
        onConnectedListeners.add(listener);
    }

    public void removeOnConnectedListener(Runnable listener) {
        onConnectedListeners.remove(listener);
    }

    public void addOnErrorListener(Consumer<String> listener) {
        onErrorListeners.add(listener);
    }

    public void removeOnErrorListener(Consumer<String> listener) {
        onErrorListeners.remove(listener);
    }
}
