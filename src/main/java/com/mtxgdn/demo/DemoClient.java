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
        System.out.println();
        printDivider();
        System.out.println("  [✓] 再见，修仙者！");
        printDivider();
    }

    private void printBanner() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║                    修 仙 世 界                               ║");
        System.out.println("  ║               XiuXian Game Demo Client                       ║");
        System.out.println("  ║                                                              ║");
        System.out.println("  ║              [ 端游 · 文字 · 修仙 · 养成 ]                   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private void printDivider() {
        System.out.println("  ──────────────────────────────────────────────────────────────");
    }

    private void showAuthMenu() {
        printDivider();
        System.out.println("  │                    【未登录】                                │");
        printDivider();
        System.out.println("  │  [1] 登录                                                  │");
        System.out.println("  │  [2] 注册                                                  │");
        System.out.println("  │  [3] 服务器状态                                             │");
        System.out.println("  │  [0] 退出                                                  │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> doLogin();
            case "2" -> doRegister();
            case "3" -> getServerStatus();
            case "0" -> running = false;
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项，请重新选择！");
            }
        }
    }

    private void doLogin() {
        printDivider();
        System.out.print("  用户名: ");
        String user = scanner.nextLine().trim();
        System.out.print("  密码: ");
        String pass = scanner.nextLine().trim();

        ApiResponse resp = authApi.login(user, pass);
        if (resp.isSuccess()) {
            token = resp.getToken();
            username = user;
            apiClient.setAuthToken(token);
            System.out.println();
            System.out.println("  [✓] 登录成功！欢迎回来，" + username);
            connectWebSocket();
            setupPlayer();
        } else {
            System.out.println();
            System.out.println("  [✗] " + resp.getMessage());
        }
    }

    private void doRegister() {
        printDivider();
        System.out.print("  用户名: ");
        String user = scanner.nextLine().trim();
        System.out.print("  密码: ");
        String pass = scanner.nextLine().trim();

        ApiResponse resp = authApi.register(user, pass);
        if (resp.isSuccess()) {
            token = resp.getToken();
            username = user;
            apiClient.setAuthToken(token);
            System.out.println();
            System.out.println("  [✓] 注册成功！欢迎，" + username);
            connectWebSocket();
            setupPlayer();
        } else {
            System.out.println();
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
                    String fromName = data.has("fromUserName") ? data.get("fromUserName").getAsString() : String.valueOf(from);
                    System.out.println();
                    System.out.println("  [世界频道] " + fromName + ": " + data.get("content").getAsString());
                    System.out.print("  请选择: ");
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
        printDivider();
        System.out.println("  │                    【主菜单】 " + username + "              │");
        printDivider();
        System.out.println("  │  [A] 角色管理    [B] 修炼系统    [C] 物品装备              │");
        System.out.println("  │  [D] 技能功法    [E] 炼丹锻造    [F] 探索秘境              │");
        System.out.println("  │  [G] 团队副本    [H] 社交系统    [I] 宗门系统              │");
        System.out.println("  │  [J] PvP对战     [K] 排行榜      [L] 地图系统              │");
        System.out.println("  │  [M] 每日任务    [N] 坊市交易    [O] 称号系统              │");
        System.out.println("  │  [Z] 系统信息    [0] 登出退出                              │");
        printDivider();
        System.out.print("  请选择类别(A-O/Z/0): ");

        String choice = scanner.nextLine().trim().toUpperCase();
        switch (choice) {
            case "A" -> showPlayerMenu();
            case "B" -> showCultivationMenu();
            case "C" -> showItemEquipmentMenu();
            case "D" -> showSkillTechniqueMenu();
            case "E" -> showCraftingMenu();
            case "F" -> showExplorationMenu();
            case "G" -> showTeamRaidMenu();
            case "H" -> showSocialMenu();
            case "I" -> showSectMenu();
            case "J" -> showPvpMenu();
            case "K" -> showRankingMenu();
            case "L" -> showMapMenu();
            case "M" -> showDailyMenu();
            case "N" -> showMarketMenu();
            case "O" -> showTitleMenu();
            case "Z" -> showSystemMenu();
            case "0" -> logout();
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项，请重新选择！");
            }
        }
    }

    private void showPlayerMenu() {
        printDivider();
        System.out.println("  │                    【角色管理】                              │");
        printDivider();
        System.out.println("  │  [1] 查看角色信息    [2] 查看境界配置    [3] 查看灵根列表    │");
        System.out.println("  │  [4] 治疗恢复        [5] 返回主菜单                          │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getPlayerInfo();
            case "2" -> getRealmConfig();
            case "3" -> getSpiritualRoots();
            case "4" -> heal();
            case "5" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showPlayerMenu();
            }
        }
    }

    private void showCultivationMenu() {
        printDivider();
        System.out.println("  │                    【修炼系统】                              │");
        printDivider();
        System.out.println("  │  [1] 开始修炼        [2] 停止修炼        [3] 境界突破        │");
        System.out.println("  │  [4] 返回主菜单                                             │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> startCultivation();
            case "2" -> stopCultivation();
            case "3" -> breakthrough();
            case "4" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showCultivationMenu();
            }
        }
    }

    private void showItemEquipmentMenu() {
        printDivider();
        System.out.println("  │                  【物品 & 装备】                             │");
        printDivider();
        System.out.println("  │  [1] 查看背包        [2] 使用物品        [3] 获取物品(GM)    │");
        System.out.println("  │  [4] 物品图鉴        [5] 查看装备        [6] 装备物品        │");
        System.out.println("  │  [7] 卸下装备        [8] 强化装备        [9] 返回主菜单       │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> showInventory();
            case "2" -> useItem();
            case "3" -> addItem();
            case "4" -> getItemRegistry();
            case "5" -> getEquipment();
            case "6" -> equipItem();
            case "7" -> unequipItem();
            case "8" -> enhanceEquipment();
            case "9" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showItemEquipmentMenu();
            }
        }
    }

    private void showSkillTechniqueMenu() {
        printDivider();
        System.out.println("  │                  【技能 & 功法】                             │");
        printDivider();
        System.out.println("  │  [1] 技能列表        [2] 我的技能        [3] 学习技能        │");
        System.out.println("  │  [4] 功法列表        [5] 我的功法        [6] 学习功法        │");
        System.out.println("  │  [7] 装备功法        [8] 卸下功法        [9] 升级功法        │");
        System.out.println("  │ [10] 返回主菜单                                             │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> showSkillLibrary();
            case "2" -> showMySkills();
            case "3" -> learnSkill();
            case "4" -> showTechniqueLibrary();
            case "5" -> showMyTechniques();
            case "6" -> learnTechnique();
            case "7" -> equipTechnique();
            case "8" -> unequipTechnique();
            case "9" -> upgradeTechnique();
            case "10" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showSkillTechniqueMenu();
            }
        }
    }

    private void showCraftingMenu() {
        printDivider();
        System.out.println("  │                    【炼丹锻造】                              │");
        printDivider();
        System.out.println("  │  [1] 查看所有配方    [2] 按类别查看    [3] 进行炼制        │");
        System.out.println("  │  [4] 返回主菜单                                             │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getRecipes("");
            case "2" -> {
                System.out.print("  输入配方类别(ALCHEMY/CRAFTING/ENHANCE): ");
                String category = scanner.nextLine().trim();
                getRecipes(category);
            }
            case "3" -> craftItem();
            case "4" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showCraftingMenu();
            }
        }
    }

    private void showExplorationMenu() {
        printDivider();
        System.out.println("  │                  【探索 & 秘境】                             │");
        printDivider();
        System.out.println("  │  [1] 游历探索        [2] 查看秘境列表    [3] 进入秘境        │");
        System.out.println("  │  [4] 返回主菜单                                             │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> exploration();
            case "2" -> listSecretRealms();
            case "3" -> enterSecretRealm();
            case "4" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showExplorationMenu();
            }
        }
    }

    private void showTeamRaidMenu() {
        printDivider();
        System.out.println("  │                  【团队 & 副本】                             │");
        printDivider();
        System.out.println("  │  [1] 创建团队        [2] 团队信息        [3] 邀请玩家        │");
        System.out.println("  │  [4] 接受邀请        [5] 离开团队        [6] 副本列表        │");
        System.out.println("  │  [7] 进入副本        [8] 返回主菜单                          │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> createTeam();
            case "2" -> getTeamInfo();
            case "3" -> invitePlayer();
            case "4" -> acceptInvite();
            case "5" -> leaveTeam();
            case "6" -> getRaidRealms();
            case "7" -> enterRaid();
            case "8" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showTeamRaidMenu();
            }
        }
    }

    private void showSocialMenu() {
        printDivider();
        System.out.println("  │                    【社交系统】                              │");
        printDivider();
        System.out.println("  │  [1] 好友列表        [2] 添加好友        [3] 好友申请        │");
        System.out.println("  │  [4] 接受申请        [5] 删除好友        [6] 世界频道        │");
        System.out.println("  │  [7] 发送私聊        [8] 返回主菜单                          │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getFriendList();
            case "2" -> addFriend();
            case "3" -> getFriendPending();
            case "4" -> acceptFriend();
            case "5" -> removeFriend();
            case "6" -> chatMenu();
            case "7" -> sendPrivateChat();
            case "8" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showSocialMenu();
            }
        }
    }

    private void chatMenu() {
        printDivider();
        System.out.println("  │                  【世界频道】                                │");
        printDivider();
        System.out.println("  │  [1] 查看聊天记录    [2] 发送消息        [3] 返回社交菜单    │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getWorldChat();
            case "2" -> sendWorldChat();
            case "3" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                chatMenu();
            }
        }
    }

    private void showSectMenu() {
        printDivider();
        System.out.println("  │                    【宗门系统】                              │");
        printDivider();
        System.out.println("  │  [1] 创建宗门        [2] 宗门成员        [3] 申请列表        │");
        System.out.println("  │  [4] 批准申请        [5] 拒绝申请        [6] 加入宗门        │");
        System.out.println("  │  [7] 退出宗门        [8] 宗门仓库        [9] 捐献物品        │");
        System.out.println("  │ [10] 取出物品       [11] 宗门升级       [12] 宗门排行榜      │");
        System.out.println("  │ [13] 返回主菜单                                             │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> createSect();
            case "2" -> getSectMembers();
            case "3" -> getSectApplications();
            case "4" -> approveApplication();
            case "5" -> rejectApplication();
            case "6" -> joinSect();
            case "7" -> leaveSect();
            case "8" -> getSectWarehouse();
            case "9" -> donateToWarehouse();
            case "10" -> takeFromWarehouse();
            case "11" -> levelUpSect();
            case "12" -> getSectTop();
            case "13" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showSectMenu();
            }
        }
    }

    private void showPvpMenu() {
        printDivider();
        System.out.println("  │                    【PvP对战】                              │");
        printDivider();
        System.out.println("  │  [1] 搜索玩家        [2] 在线玩家        [3] 发起挑战        │");
        System.out.println("  │  [4] 返回主菜单                                             │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> searchPlayers();
            case "2" -> showOnlinePlayers();
            case "3" -> pvpChallenge();
            case "4" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showPvpMenu();
            }
        }
    }

    private void showRankingMenu() {
        printDivider();
        System.out.println("  │                    【排行榜】                                │");
        printDivider();
        System.out.println("  │  [1] 境界排行        [2] 战力排行        [3] 财富排行        │");
        System.out.println("  │  [4] 返回主菜单                                             │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getRanking("realm", 10);
            case "2" -> getRanking("power", 10);
            case "3" -> getRanking("wealth", 10);
            case "4" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showRankingMenu();
            }
        }
    }

    private void showMapMenu() {
        printDivider();
        System.out.println("  │                    【地图系统】                              │");
        printDivider();
        System.out.println("  │  [1] 当前位置        [2] 地图地点        [3] 移动到地点      │");
        System.out.println("  │  [4] 返回主菜单                                             │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getMapSurroundings();
            case "2" -> getMapLocations();
            case "3" -> travelTo();
            case "4" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showMapMenu();
            }
        }
    }

    private void showDailyMenu() {
        printDivider();
        System.out.println("  │                    【每日任务】                              │");
        printDivider();
        System.out.println("  │  [1] 每日信息        [2] 晨练修行        [3] 返回主菜单      │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getDailyInfo();
            case "2" -> morningCultivation();
            case "3" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showDailyMenu();
            }
        }
    }

    private void showMarketMenu() {
        printDivider();
        System.out.println("  │                    【坊市交易】                              │");
        printDivider();
        System.out.println("  │  [1] 坊市列表        [2] 上架物品        [3] 我的上架        │");
        System.out.println("  │  [4] 购买物品        [5] 取消上架        [6] 返回主菜单      │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getMarketListings();
            case "2" -> listItem();
            case "3" -> getMyListings();
            case "4" -> buyItem();
            case "5" -> cancelListing();
            case "6" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showMarketMenu();
            }
        }
    }

    private void showTitleMenu() {
        printDivider();
        System.out.println("  │                    【称号系统】                              │");
        printDivider();
        System.out.println("  │  [1] 所有称号        [2] 我的称号        [3] 当前称号        │");
        System.out.println("  │  [4] 装备称号        [5] 卸下称号        [6] 返回主菜单      │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getAllTitles();
            case "2" -> getMyTitles();
            case "3" -> getActiveTitle();
            case "4" -> equipTitle();
            case "5" -> unequipTitle();
            case "6" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showTitleMenu();
            }
        }
    }

    private void showSystemMenu() {
        printDivider();
        System.out.println("  │                    【系统信息】                              │");
        printDivider();
        System.out.println("  │  [1] 服务器状态      [2] 在线玩家        [3] 返回主菜单      │");
        printDivider();
        System.out.print("  请选择: ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> getServerStatus();
            case "2" -> showOnlinePlayers();
            case "3" -> {}
            default -> {
                System.out.println();
                System.out.println("  [✗] 无效选项！");
                showSystemMenu();
            }
        }
    }

    private void getPlayerInfo() {
        ApiResponse resp = gameApi.getPlayerInfo();
        printApiResponse(resp);
    }

    private void getRealmConfig() {
        ApiResponse resp = gameApi.getRealmConfig();
        printApiResponse(resp);
    }

    private void getSpiritualRoots() {
        ApiResponse resp = gameApi.getSpiritualRoots();
        printApiResponse(resp);
    }

    private void heal() {
        ApiResponse resp = gameApi.heal();
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

    private void addItem() {
        System.out.print("  物品Key: ");
        String itemKey = scanner.nextLine().trim();
        System.out.print("  数量: ");
        int quantity = Integer.parseInt(scanner.nextLine().trim());
        ApiResponse resp = gameApi.addItem(itemKey, quantity);
        printApiResponse(resp);
    }

    private void getItemRegistry() {
        ApiResponse resp = gameApi.getItemRegistry();
        printApiResponse(resp);
    }

    private void getEquipment() {
        ApiResponse resp = gameApi.getEquipment();
        printApiResponse(resp);
    }

    private void equipItem() {
        System.out.print("  物品Key: ");
        String itemKey = scanner.nextLine().trim();
        System.out.print("  装备槽位(weapon/armor/accessory/helmet/boots): ");
        String slot = scanner.nextLine().trim();
        ApiResponse resp = gameApi.equipItem(itemKey, slot);
        printApiResponse(resp);
    }

    private void unequipItem() {
        System.out.print("  装备槽位(weapon/armor/accessory/helmet/boots): ");
        String slot = scanner.nextLine().trim();
        ApiResponse resp = gameApi.unequipItem(slot);
        printApiResponse(resp);
    }

    private void enhanceEquipment() {
        System.out.print("  装备槽位(weapon/armor/accessory/helmet/boots): ");
        String slot = scanner.nextLine().trim();
        ApiResponse resp = gameApi.enhanceEquipment(slot);
        printApiResponse(resp);
    }

    private void showSkillLibrary() {
        ApiResponse resp = gameApi.getSkillList();
        printApiResponse(resp);
    }

    private void showMySkills() {
        ApiResponse resp = gameApi.getMySkills();
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

    private void showTechniqueLibrary() {
        ApiResponse resp = gameApi.getTechniques();
        printApiResponse(resp);
    }

    private void showMyTechniques() {
        ApiResponse resp = gameApi.getMyTechniques();
        printApiResponse(resp);
    }

    private void learnTechnique() {
        System.out.print("  功法ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long techniqueId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.learnTechnique(techniqueId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的功法ID");
        }
    }

    private void equipTechnique() {
        System.out.print("  功法ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long techniqueId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.equipTechnique(techniqueId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的功法ID");
        }
    }

    private void unequipTechnique() {
        System.out.print("  功法ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long techniqueId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.unequipTechnique(techniqueId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的功法ID");
        }
    }

    private void upgradeTechnique() {
        System.out.print("  功法ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long techniqueId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.upgradeTechnique(techniqueId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的功法ID");
        }
    }

    private void getRecipes(String category) {
        ApiResponse resp = gameApi.getRecipes(category);
        printApiResponse(resp);
    }

    private void craftItem() {
        System.out.print("  配方ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long recipeId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.craftItem(recipeId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的配方ID");
        }
    }

    private void getDailyInfo() {
        ApiResponse resp = gameApi.getDailyInfo();
        printApiResponse(resp);
    }

    private void morningCultivation() {
        ApiResponse resp = gameApi.morningCultivation();
        printApiResponse(resp);
    }

    private void getMarketListings() {
        ApiResponse resp = gameApi.getMarketListings();
        printApiResponse(resp);
    }

    private void listItem() {
        System.out.print("  物品Key: ");
        String itemKey = scanner.nextLine().trim();
        System.out.print("  数量: ");
        int quantity = Integer.parseInt(scanner.nextLine().trim());
        System.out.print("  售价灵石: ");
        long price = Long.parseLong(scanner.nextLine().trim());
        ApiResponse resp = gameApi.listItem(itemKey, quantity, price);
        printApiResponse(resp);
    }

    private void buyItem() {
        System.out.print("  商品ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long listingId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.buyItem(listingId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的商品ID");
        }
    }

    private void cancelListing() {
        System.out.print("  商品ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long listingId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.cancelListing(listingId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的商品ID");
        }
    }

    private void getMyListings() {
        ApiResponse resp = gameApi.getMyListings();
        printApiResponse(resp);
    }

    private void getRanking(String type, int limit) {
        ApiResponse resp = gameApi.getRanking(type, limit);
        printApiResponse(resp);
    }

    private void searchPlayers() {
        System.out.print("  玩家名称: ");
        String name = scanner.nextLine().trim();
        ApiResponse resp = gameApi.searchPlayers(name);
        printApiResponse(resp);
    }

    private void getFriendList() {
        ApiResponse resp = gameApi.getFriendList();
        printApiResponse(resp);
    }

    private void getFriendPending() {
        ApiResponse resp = gameApi.getFriendPending();
        printApiResponse(resp);
    }

    private void addFriend() {
        System.out.print("  目标玩家ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long targetId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.addFriend(targetId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的玩家ID");
        }
    }

    private void acceptFriend() {
        System.out.print("  申请者玩家ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long requesterId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.acceptFriend(requesterId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的玩家ID");
        }
    }

    private void removeFriend() {
        System.out.print("  好友玩家ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long friendId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.removeFriend(friendId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的玩家ID");
        }
    }

    private void createSect() {
        System.out.print("  宗门名称: ");
        String name = scanner.nextLine().trim();
        System.out.print("  宗门描述: ");
        String desc = scanner.nextLine().trim();
        ApiResponse resp = gameApi.createSect(name, desc);
        printApiResponse(resp);
    }

    private void joinSect() {
        System.out.print("  宗门ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long sectId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.joinSect(sectId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的宗门ID");
        }
    }

    private void getSectMembers() {
        ApiResponse resp = gameApi.getSectMembers();
        printApiResponse(resp);
    }

    private void getSectApplications() {
        ApiResponse resp = gameApi.getSectApplications();
        printApiResponse(resp);
    }

    private void approveApplication() {
        System.out.print("  申请ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long appId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.approveApplication(appId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的申请ID");
        }
    }

    private void rejectApplication() {
        System.out.print("  申请ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long appId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.rejectApplication(appId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的申请ID");
        }
    }

    private void leaveSect() {
        ApiResponse resp = gameApi.leaveSect();
        printApiResponse(resp);
    }

    private void getSectWarehouse() {
        ApiResponse resp = gameApi.getSectWarehouse();
        printApiResponse(resp);
    }

    private void donateToWarehouse() {
        System.out.print("  物品Key: ");
        String itemKey = scanner.nextLine().trim();
        System.out.print("  数量: ");
        int quantity = Integer.parseInt(scanner.nextLine().trim());
        ApiResponse resp = gameApi.donateToWarehouse(itemKey, quantity);
        printApiResponse(resp);
    }

    private void takeFromWarehouse() {
        System.out.print("  物品Key: ");
        String itemKey = scanner.nextLine().trim();
        System.out.print("  数量: ");
        int quantity = Integer.parseInt(scanner.nextLine().trim());
        ApiResponse resp = gameApi.takeFromWarehouse(itemKey, quantity);
        printApiResponse(resp);
    }

    private void levelUpSect() {
        ApiResponse resp = gameApi.levelUpSect();
        printApiResponse(resp);
    }

    private void getSectTop() {
        ApiResponse resp = gameApi.getSectTop();
        printApiResponse(resp);
    }

    private void getMapSurroundings() {
        ApiResponse resp = gameApi.getMapSurroundings();
        printApiResponse(resp);
    }

    private void travelTo() {
        System.out.print("  地点ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long locationId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.travelTo(locationId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的地点ID");
        }
    }

    private void getMapLocations() {
        ApiResponse resp = gameApi.getMapLocations();
        printApiResponse(resp);
    }

    private void getAllTitles() {
        ApiResponse resp = gameApi.getAllTitles();
        printApiResponse(resp);
    }

    private void getMyTitles() {
        ApiResponse resp = gameApi.getMyTitles();
        printApiResponse(resp);
    }

    private void getActiveTitle() {
        ApiResponse resp = gameApi.getActiveTitle();
        printApiResponse(resp);
    }

    private void equipTitle() {
        System.out.print("  称号Key: ");
        String titleKey = scanner.nextLine().trim();
        ApiResponse resp = gameApi.equipTitle(titleKey);
        printApiResponse(resp);
    }

    private void unequipTitle() {
        ApiResponse resp = gameApi.unequipTitle();
        printApiResponse(resp);
    }

    private void createTeam() {
        ApiResponse resp = gameApi.createTeam();
        printApiResponse(resp);
    }

    private void getTeamInfo() {
        ApiResponse resp = gameApi.getTeamInfo();
        printApiResponse(resp);
    }

    private void invitePlayer() {
        System.out.print("  目标玩家ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long targetId = Long.parseLong(idStr);
            ApiResponse resp = gameApi.invitePlayer(targetId);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的玩家ID");
        }
    }

    private void acceptInvite() {
        ApiResponse resp = gameApi.acceptInvite();
        printApiResponse(resp);
    }

    private void leaveTeam() {
        ApiResponse resp = gameApi.leaveTeam();
        printApiResponse(resp);
    }

    private void getRaidRealms() {
        ApiResponse resp = gameApi.getRaidRealms();
        printApiResponse(resp);
    }

    private void enterRaid() {
        System.out.print("  副本名称: ");
        String areaName = scanner.nextLine().trim();
        ApiResponse resp = gameApi.enterRaid(areaName);
        printApiResponse(resp);
    }

    private void getWorldChat() {
        ApiResponse resp = gameApi.getWorldChat(20);
        printApiResponse(resp);
    }

    private void sendWorldChat() {
        System.out.print("  聊天内容: ");
        String content = scanner.nextLine().trim();
        if (content.isEmpty()) return;
        ApiResponse resp = gameApi.sendWorldChat(content);
        printApiResponse(resp);
    }

    private void sendPrivateChat() {
        System.out.print("  目标玩家ID: ");
        String idStr = scanner.nextLine().trim();
        try {
            long targetId = Long.parseLong(idStr);
            System.out.print("  聊天内容: ");
            String content = scanner.nextLine().trim();
            if (content.isEmpty()) {
                System.out.println("  [✗] 内容不能为空");
                return;
            }
            ApiResponse resp = gameApi.sendPrivateChat(targetId, content);
            printApiResponse(resp);
        } catch (NumberFormatException e) {
            System.out.println("  [✗] 请输入有效的玩家ID");
        }
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

    private void getServerStatus() {
        ApiResponse resp = gameApi.getServerStatus();
        printApiResponse(resp);
    }

    private void showOnlinePlayers() {
        ApiResponse resp = gameApi.getAllPlayers(20, 0);
        printApiResponse(resp);
    }

    private void logout() {
        authApi.logout();
        token = null;
        username = null;
        wsClient.disconnect();
        System.out.println();
        System.out.println("  [✓] 已登出");
    }

    private void printApiResponse(ApiResponse resp) {
        System.out.println();
        System.out.println("  ┌─ 响应结果 ───────────────────────────────");
        System.out.println("  │ 状态码 : " + resp.getCode());
        System.out.println("  │ 消息   : " + resp.getMessage());

        JsonObject data = resp.getData();
        if (data != null && data.size() > 0) {
            System.out.println("  │ 数据   :");
            printJson(data, "    ");
        }
        System.out.println("  └─────────────────────────────────────────");
    }

    private void printJson(JsonObject obj, String indent) {
        for (String key : obj.keySet()) {
            Object val = obj.get(key);
            if (val instanceof JsonArray arr) {
                System.out.println("  " + indent + key + ": [" + arr.size() + " 项]");
                int show = Math.min(arr.size(), 8);
                for (int i = 0; i < show; i++) {
                    Object item = arr.get(i);
                    if (item instanceof JsonObject innerObj) {
                        System.out.println("  " + indent + "  ├─ {");
                        printJson(innerObj, indent + "    ");
                        System.out.println("  " + indent + "  │ }");
                    } else {
                        System.out.println("  " + indent + "  ├─ " + item);
                    }
                }
                if (arr.size() > show) {
                    System.out.println("  " + indent + "  └─ ... 还有 " + (arr.size() - show) + " 项");
                }
            } else if (val instanceof JsonObject inner) {
                System.out.println("  " + indent + key + ": {");
                printJson(inner, indent + "  ");
                System.out.println("  " + indent + "}");
            } else {
                String valStr = val != null ? val.toString() : "null";
                if (valStr.length() > 80) {
                    valStr = valStr.substring(0, 77) + "...";
                }
                System.out.println("  " + indent + key + " = " + valStr);
            }
        }
    }

    public static void main(String[] args) {
        DemoClient client = new DemoClient();
        client.start();
    }
}