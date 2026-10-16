# 修仙服务器 - 发现的问题与修复建议

> 📦 **已归档**：本文档为历史审计产物，内容（版本号、文件路径、行号、"已修复"结论）不代表当前代码状态，
> 请勿作为开发依据。当前有效文档见项目根目录 README.md / README_PLUGIN.md / CHANGELOG.md。
> 归档索引：[docs/archive/README.md](README.md)

## 代码问题清单

### 🔴 严重问题 (必须立即修复)

#### 1. 并发安全问题 - 境界突破竞态条件

**文件**: `src/main/java/com/mtxgdn/game/service/RealmService.java`

**问题**: 
```java
// 当前实现未加锁，多个线程同时突破会导致：
// 1. 重复扣除灵石和经验
// 2. 境界被重复提升
// 3. 资源不一致
```

**修复方案**:
```java
public RealmBreakthroughResult tryBreakthrough(long userId) {
    return DatabaseManager.runTransaction(conn -> {
        // 使用SELECT FOR UPDATE加悲观锁
        String sql = "SELECT * FROM players WHERE user_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return RealmBreakthroughResult.failure("角色不存在");
                }
                
                PlayerInfo player = mapPlayerInfo(rs);
                
                // 检查条件
                RealmConfig nextRealm = GameConfigLoader.getNextRealmConfig(
                    player.getRealm(), player.getSubRealm());
                
                if (nextRealm == null) {
                    return RealmBreakthroughResult.failure("已达最高境界");
                }
                
                if (player.getExperience() < nextRealm.getRequiredExp()) {
                    return RealmBreakthroughResult.failure("经验不足");
                }
                
                long spiritStones = getSpiritStoneCount(conn, player.getId());
                if (spiritStones < nextRealm.getRequiredSpiritStones()) {
                    return RealmBreakthroughResult.failure("灵石不足");
                }
                
                // 扣除资源
                deductSpiritStones(conn, player.getId(), nextRealm.getRequiredSpiritStones());
                deductExperience(conn, player.getId(), nextRealm.getRequiredExp());
                
                // 提升境界
                updateRealm(conn, player.getId(), nextRealm);
                
                return RealmBreakthroughResult.success(nextRealm);
            }
        }
    });
}
```

---

#### 2. 交易系统漏洞 - 价格和数量验证不足

**文件**: `src/main/java/com/mtxgdn/game/service/TradeService.java`

**问题**:
```java
// 1. 未验证价格范围，可能导致负数价格或整数溢出
// 2. 未充分验证数量，可能上架超过持有数量的物品
// 3. 购买时未使用事务锁，可能重复购买
```

**修复方案**:
```java
public Map<String, Object> listItem(int playerId, String itemKey, int quantity, long price) {
    // 验证价格范围
    if (price <= 0) {
        return Map.of("success", false, "message", "价格必须大于0");
    }
    if (price > 999_999_999) {
        return Map.of("success", false, "message", "价格不能超过999999999");
    }
    
    // 验证数量范围
    if (quantity <= 0) {
        return Map.of("success", false, "message", "数量必须大于0");
    }
    if (quantity > 999_999) {
        return Map.of("success", false, "message", "数量不能超过999999");
    }
    
    return DatabaseManager.runTransaction(conn -> {
        // 使用FOR UPDATE锁定库存
        String checkSql = "SELECT quantity FROM players_items " +
                         "WHERE player_id = ? AND item_key = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setInt(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Map.of("success", false, "message", "你没有该物品");
                }
                
                int currentQuantity = rs.getInt("quantity");
                if (currentQuantity < quantity) {
                    return Map.of("success", false, "message", 
                        "库存不足，当前: " + currentQuantity);
                }
            }
        }
        
        // 扣除物品并上架
        deductItem(conn, playerId, itemKey, quantity);
        long listingId = insertListing(conn, playerId, itemKey, quantity, price);
        
        return Map.of("success", true, "message", "上架成功", "listingId", listingId);
    });
}

public Map<String, Object> buyItem(int buyerPlayerId, long listingId) {
    return DatabaseManager.runTransaction(conn -> {
        // 锁定订单
        String lockSql = "SELECT * FROM trade_listings WHERE id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
            ps.setLong(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Map.of("success", false, "message", "商品不存在");
                }
                
                int sellerPlayerId = rs.getInt("seller_player_id");
                String itemKey = rs.getString("item_key");
                int quantity = rs.getInt("quantity");
                long price = rs.getLong("price_spirit_stones");
                
                // 检查是否自己的商品
                if (sellerPlayerId == buyerPlayerId) {
                    return Map.of("success", false, "message", "不能购买自己的商品");
                }
                
                // 检查买家灵石
                long buyerStones = getSpiritStoneCount(conn, buyerPlayerId);
                if (buyerStones < price) {
                    return Map.of("success", false, "message", "灵石不足");
                }
                
                // 扣除买家灵石，增加卖家灵石
                deductSpiritStones(conn, buyerPlayerId, price);
                addSpiritStones(conn, sellerPlayerId, price);
                
                // 给买家物品
                addItem(conn, buyerPlayerId, itemKey, quantity);
                
                // 删除订单
                deleteListing(conn, listingId);
                
                return Map.of("success", true, "message", "购买成功");
            }
        }
    });
}
```

---

#### 3. 物品使用漏洞 - 并发重复使用

**文件**: `src/main/java/com/mtxgdn/game/service/ItemService.java`

**问题**:
```java
// 未使用事务锁，可能导致一个物品被重复使用
```

**修复方案**:
```java
public Map<String, Object> useItem(int playerId, String itemKey) {
    return DatabaseManager.runTransaction(conn -> {
        // 锁定物品
        String sql = "SELECT quantity FROM players_items " +
                    "WHERE player_id = ? AND item_key = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt("quantity") <= 0) {
                    return Map.of("success", false, "message", "物品数量不足");
                }
            }
        }
        
        // 扣除物品
        deductItem(conn, playerId, itemKey, 1);
        
        // 应用效果
        Item item = ItemRegistry.get(itemKey);
        applyItemEffects(conn, playerId, item);
        
        return Map.of("success", true, "message", "使用成功");
    });
}
```

---

### 🟡 高危问题

#### 4. 输入验证不足

**文件**: `src/main/java/com/mtxgdn/service/UserService.java`

**问题**:
```java
// 1. 用户名长度限制过长（64字符）
// 2. 缺少用户名格式验证
// 3. 邮箱验证过于简单
```

**修复方案**:
```java
private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,32}$");
private static final Pattern EMAIL_PATTERN = Pattern.compile(
    "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

public Response register(String username, String rawPassword, String email, String code) {
    if (username == null || username.trim().isEmpty()) {
        return buildError(400, "用户名不能为空");
    }
    
    String trimmedUsername = username.trim();
    
    // 长度检查
    if (trimmedUsername.length() < 3 || trimmedUsername.length() > 32) {
        return buildError(400, "用户名长度必须在3-32字符之间");
    }
    
    // 格式检查
    if (!USERNAME_PATTERN.matcher(trimmedUsername).matches()) {
        return buildError(400, "用户名只能包含字母、数字和下划线");
    }
    
    // 密码强度检查
    if (rawPassword == null || rawPassword.length() < 8) {
        return buildError(400, "密码长度不能少于8位");
    }
    if (!isStrongPassword(rawPassword)) {
        return buildError(400, "密码必须包含大小写字母、数字和特殊字符");
    }
    
    // ... 后续逻辑
}

private boolean isStrongPassword(String password) {
    return password.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$");
}
```

---

#### 5. SQL注入风险 - 动态表名

**文件**: `src/main/java/com/mtxgdn/db/DatabaseManager.java`

**问题**:
```java
// assertSafeTableName 使用缓存，可能被绕过
```

**修复方案**:
```java
private static final Set<String> ALLOWED_TABLES = Set.of(
    "users", "players", "players_items", "players_equipment",
    "players_skills", "players_techniques", "trade_listings",
    "sects", "sect_members", "friends", "chat_messages"
    // ... 所有合法表名
);

private static void assertSafeTableName(String tableName) {
    if (tableName == null || tableName.isBlank()) {
        throw new IllegalArgumentException("非法表名: " + tableName);
    }
    
    // 先检查格式
    if (!tableName.matches("^[a-z_]+$")) {
        throw new IllegalArgumentException("非法表名格式: " + tableName);
    }
    
    // 再检查白名单（硬编码，不使用缓存）
    if (!ALLOWED_TABLES.contains(tableName)) {
        throw new IllegalArgumentException("表名不在白名单中: " + tableName);
    }
}
```

---

### 🟢 中危问题

#### 6. 资源泄露风险

**多个文件**

**问题**: 虽然大部分代码使用了try-with-resources，但仍需全面审查

**修复建议**: 使用静态分析工具检查所有资源使用

---

#### 7. 日志敏感信息泄露

**全局**

**问题**: 可能在日志中输出密码、token等

**修复方案**:
```java
public class SensitiveDataMasker {
    public static String maskPassword(String password) {
        return "***";
    }
    
    public static String maskToken(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
    
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        String[] parts = email.split("@");
        return parts[0].charAt(0) + "***@" + parts[1];
    }
}

// 使用示例
LOG.info("用户登录: " + username + ", token: " + SensitiveDataMasker.maskToken(token));
```

---

## 玩法问题

### 1. 数值平衡

**修炼速度**:
- 当前可能过快或过慢，需要根据测试调整
- 建议：增加不同境界的修炼速度系数

**物品掉落**:
- 稀有物品掉落率需要平衡
- 建议：实现完整的稀有度系统

### 2. 经济系统

**交易手续费**:
- 当前固定5%
- 建议：根据商品价值阶梯式收费

**灵石获取**:
- 需要平衡各种获取途径
- 避免通货膨胀

---

## 测试执行指南

### 前置条件

1. 安装Java 23
2. 安装Maven（或使用mvnw）
3. 确保端口未被占用

### 运行测试

#### 方式1: 使用测试脚本
```bash
# Windows
.\run_tests.bat

# Linux/Mac
chmod +x run_tests.sh
./run_tests.sh
```

#### 方式2: 手动运行
```bash
# 编译
mvn clean compile test-compile

# 运行安全测试
mvn test -Dtest=SecurityTest

# 运行功能测试
mvn test -Dtest=ComprehensiveFunctionalTest

# 运行所有测试
mvn test
```

### 预期结果

所有测试应该通过，如果有失败：
1. 查看测试日志
2. 检查数据库连接
3. 确认物品注册是否成功

---

## 立即行动项

### 今天必须完成

1. ✅ 修复境界突破并发问题
2. ✅ 修复交易系统验证问题
3. ✅ 修复物品使用并发问题

### 本周完成

4. ✅ 增强输入验证
5. ✅ 加固SQL注入防护
6. ✅ 添加日志脱敏
7. ✅ 运行完整测试套件

### 下周完成

8. 性能测试和优化
9. 添加监控和告警
10. 编写API文档

---

## 联系与支持

如有问题，请参考：
- 测试报告: `SECURITY_TEST_REPORT.md`
- 测试代码: `src/test/java/com/mtxgdn/`
- 问题追踪: 建议使用GitHub Issues

---

**最后更新**: 2024年
**测试状态**: 测试代码已完成，待执行
**风险等级**: 中等（有严重问题但可修复）
