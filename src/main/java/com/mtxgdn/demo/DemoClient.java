package com.mtxgdn.demo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.client.ApiClient;
import com.mtxgdn.client.AuthApi;
import com.mtxgdn.client.GameApi;
import com.mtxgdn.client.GameWebSocketClient;
import com.mtxgdn.common.ApiResponse;

import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DemoClient {

    private static final Scanner scanner = new Scanner(System.in);

    private final ApiClient apiClient;
    private final AuthApi authApi;
    private final GameApi gameApi;
    private final GameWebSocketClient wsClient;

    private String token;
    private String username;
    private boolean running = true;

    public DemoClient() {
        this.apiClient = new ApiClient();
        this.authApi = new AuthApi(apiClient);
        this.gameApi = new GameApi(apiClient);
        this.wsClient = new GameWebSocketClient();
    }

    public void start() {
        printBanner();

        while (running) {
            if (!apiClient.isAuthenticated()) {
                showAuthMenu();
            } else {
                showMainMenu();
            }
        }

        wsClient.disconnect();
        System.out.println("\n  再见，修仙者！");
    }

    private void printBanner() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════╗");
        System.out.println("  ║       修 仙 世 界  -  Demo 客户端     ║");
        System.out.println("  ║       XiuXian Game Debug Client      ║");
        System.out.println("  ╚══════════════════════════════════════╝");
        System.out.println();
    }

    private void showAuthMenu() {
        System.out.println("  ┌── 未登录 ──────────────────────────┐");
        System.out.println("  │  1. 登录                            │");
        System.out.println("  │  2. 注册                            │");
        System.out.println("  │  3. 查看服务器状态                   │");
        System.out.println("  │  0. 退出                            │");
        System.out.println("  └─────────────────────────────────────┘");
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> doLogin();
            case "2" -> doRegister();
            case "3" -> getServerStatus();
            case "0" -> running = false;
            default -> System.out.println("  无效选项！");
        }
    }

    private void doLogin() {
        System.out.print("  用户名: ");
        String user = scanner.nextLine().trim();
        System.out.print("  密码: ");
        String pass = scanner.nextLine().trim();

        ApiResponse resp = authApi.login(user, pass);
        if (resp.isSuccess()) {
            token = resp.getToken();
            username = user;
            apiClient.setAuthToken(token);
            System.out.println("  [✓] 登录成功！欢迎回来，" + username);
            connectWebSocket();
            setupPlayer();
        } else {
            System.out.println("  [✗] " + resp.getMessage());
        }
    }

    private void doRegister() {
        System.out.print("  用户名: ");
        String user = scanner.nextLine().trim();
        System.out.print("  密码: ");
        String pass = scanner.nextLine().trim();

        ApiResponse resp = authApi.register(user, pass);
        if (resp.isSuccess()) {
            token = resp.getToken();
            username = user;
            apiClient.setAuthToken(token);
            System.out.println("  [✓] 注册成功！欢迎，" + username);
            connectWebSocket();
            setupPlayer();
        } else {
            System.out.println("  [✗] " + resp.getMessage());
        }
    }

    private void connectWebSocket() {
        CountDownLatch latch = new CountDownLatch(1);
        wsClient.addOnConnectedListener(() -> {
            System.out.println("  [✓] WebSocket 已连接");
            latch.countDown();
        });
        wsClient.addMessageListener(msg -> {
            if ("chat".equals(msg.getType())) {
                JsonObject data = msg.getData();
                if (data != null && data.has("content")) {
                    long from = data.has("fromUserId") ? data.get("fromUserId").getAsLong() : 0;
                    System.out.println("  [聊天] " + from + ": " + data.get("content").getAsString());
                }
            }
        });
        wsClient.connect(token);

        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private void setupPlayer() {
        ApiResponse resp = gameApi.getPlayerInfo();
        if (resp.isSuccess()) {
            JsonObject data = resp.getData();
            if (data == null || !data.has("name")) {
                System.out.print("  你还没有角色，输入角色名创建: ");
                String name = scanner.nextLine().trim();
                if (name.isEmpty()) name = username + "的修仙号";
                ApiResponse createResp = gameApi.createPlayer(name);
                if (createResp.isSuccess()) {
                    System.out.println("  [✓] 角色「" + name + "」创建成功！");
                    System.out.println("      境界: 凡人 | HP: 100 | 攻击: 10");
                } else {
                    System.out.println("  [✗] " + createResp.getMessage());
                }
            } else {
                String playerName = data.get("name").getAsString();
                String realm = data.has("realmName") ? data.get("realmName").getAsString() : "凡人";
                int level = data.has("level") ? data.get("level").getAsInt() : 1;
                int hp = data.has("hp") ? data.get("hp").getAsInt() : 0;
                int maxHp = data.has("maxHp") ? data.get("maxHp").getAsInt() : 0;
                System.out.println("  [✓] 角色已存在：「" + playerName + "」" + realm + " Lv." + level + " HP:" + hp + "/" + maxHp);
            }
        }
    }

    private void showMainMenu() {
        System.out.println();
        System.out.println("  ┌── 主菜单 [" + username + "] ──────────────────┐");
        System.out.println("  │  [角色]                                    │");
        System.out.println("  │   1. 查看角色信息                           │");
        System.out.println("  │   2. 开始修炼                               │");
        System.out.println("  │   3. 停止修炼                               │");
        System.out.println("  │   4. 境界突破                               │");
        System.out.println("  │  [探索]                                    │");
        System.out.println("  │   5. 游历探索                               │");
        System.out.println("  │   6. 秘境探索                               │");
        System.out.println("  │   7. 查看秘境列表                           │");
        System.out.println("  │  [物品 & 技能]                             │");
        System.out.println("  │   8. 查看背包                               │");
        System.out.println("  │   9. 使用物品                               │");
        System.out.println("  │  10. 查看技能库                             │");
        System.out.println("  │  11. 学习技能                               │");
        System.out.println("  │  12. 我的技能                               │");
        System.out.println("  │  [PvP]                                     │");
        System.out.println("  │  13. PvP 挑战                               │");
        System.out.println("  │  [系统]                                    │");
        System.out.println("  │  14. 查看在线玩家                           │");
        System.out.println("  │  15. 服务器状态                             │");
        System.out.println("  │  16. 发送聊天                               │");
        System.out.println("  │   0. 登出                                   │");
        System.out.println("  └─────────────────────────────────────────────┘");
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getPlayerInfo();
            case "2" -> startCultivation();
            case "3" -> stopCultivation();
            case "4" -> breakthrough();
            case "5" -> exploration();
            case "6" -> enterSecretRealm();
            case "7" -> listSecretRealms();
            case "8" -> showInventory();
            case "9" -> useItem();
            case "10" -> showSkillLibrary();
            case "11" -> learnSkill();
            case "12" -> showMySkills();
            case "13" -> pvpChallenge();
            case "14" -> showOnlinePlayers();
            case "15" -> getServerStatus();
            case "16" -> sendChat();
            case "0" -> logout();
            default -> System.out.println("  无效选项！");
        }
    }

    private void getPlayerInfo() {
        ApiResponse resp = gameApi.getPlayerInfo();
        printApiResponse(resp);
    }

    private void startCultivation() {
        ApiResponse resp = gameApi.startCultivation();
        printApiResponse(resp);
    }

    private void stopCultivation() {
        ApiResponse resp = gameApi.stopCultivation();
        printApiResponse(resp);
    }

    private void breakthrough() {
        ApiResponse resp = gameApi.breakthrough();
        printApiResponse(resp);
    }

    private void exploration() {
        ApiResponse resp = gameApi.explore();
        printApiResponse(resp);
    }

    private void listSecretRealms() {
        ApiResponse resp = gameApi.getSecretRealmAreas();
        printApiResponse(resp);
    }

    private void enterSecretRealm() {
        System.out.print("  秘境名称: ");
        String area = scanner.nextLine().trim();
        ApiResponse resp = gameApi.enterSecretRealm(area);
        printApiResponse(resp);
    }

    private void showInventory() {
        ApiResponse resp = gameApi.getInventory();
        printApiResponse(resp);
    }

    private void useItem() {
        System.out.print("  物品Key: ");
        String itemKey = scanner.nextLine().trim();
        ApiResponse resp = gameApi.useItem(itemKey);
        printApiResponse(resp);
    }

    private void showSkillLibrary() {
        ApiResponse resp = gameApi.getSkillList();
        printApiResponse(resp);
    }

    private void learnSkill() {
        System.out.print("  技能ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long skillId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.learnSkill(skillId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的技能ID");
        }
    }

    private void showMySkills() {
        ApiResponse resp = gameApi.getMySkills();
        printApiResponse(resp);
    }

    private void pvpChallenge() {
        System.out.print("  对手玩家ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long targetId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.pvpChallenge(targetId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的玩家ID");
        }
    }

    private void showOnlinePlayers() {
        ApiResponse resp = gameApi.getAllPlayers(20, 0);
        printApiResponse(resp);
    }

    private void getServerStatus() {
        ApiResponse resp = gameApi.getServerStatus();
        printApiResponse(resp);
    }

    private void sendChat() {
        System.out.print("  聊天内容: ");
        String content = scanner.nextLine().trim();
        if (content.isEmpty()) return;
        JsonObject data = new JsonObject();
        data.addProperty("content", content);
        wsClient.send("chat", data);
        System.out.println("  [✓] 已发送");
    }

    private void logout() {
        authApi.logout();
        token = null;
        username = null;
        wsClient.disconnect();
        System.out.println("  已登出");
    }

    private void printApiResponse(ApiResponse resp) {
        System.out.println("  ┌─ 响应 ────────────────────────────────");
        System.out.println("  │ code   : " + resp.getCode());
        System.out.println("  │ message: " + resp.getMessage());

        JsonObject data = resp.getData();
        if (data != null && data.size() > 0) {
            System.out.println("  │ data   :");
            for (String key : data.keySet()) {
                Object val = data.get(key);
                if (val instanceof JsonArray arr) {
                    System.out.println("  │   " + key + ": [" + arr.size() + " items]");
                    int show = Math.min(arr.size(), 5);
                    for (int i = 0; i < show; i++) {
                        System.out.println("  │     " + arr.get(i));
                    }
                    if (arr.size() > 5) {
                        System.out.println("  │     ... 还有 " + (arr.size() - 5) + " 条");
                    }
                } else if (val instanceof JsonObject inner) {
                    System.out.println("  │   " + key + ":");
                    for (String ik : inner.keySet()) {
                        System.out.println("  │     " + ik + " = " + inner.get(ik));
                    }
                } else {
                    System.out.println("  │   " + key + " = " + val);
                }
            }
        }
        System.out.println("  └────────────────────────────────────────");
    }

    public static void main(String[] args) {
        DemoClient client = new DemoClient();
        client.start();
    }
}
