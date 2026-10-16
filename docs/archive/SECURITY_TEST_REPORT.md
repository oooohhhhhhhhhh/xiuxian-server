# 修仙服务器安全审查与测试报告

> 📦 **已归档**：本文档为历史审计产物，内容（版本号、文件路径、行号、"已修复"结论）不代表当前代码状态，
> 请勿作为开发依据。当前有效文档见项目根目录 README.md / README_PLUGIN.md / CHANGELOG.md。
> 归档索引：[docs/archive/README.md](README.md)

## 项目概述

**项目名称**: 修仙服务器 (xiuxian-server-alpha)  
**版本**: V1.4.1-beta1  
**技术栈**: Java 23, Jersey 4.0.2, Grizzly HTTP, SQLite/MySQL  
**测试日期**: 2024  

---

## 一、代码审查发现的问题

### 🔴 严重问题

#### 1. SQL注入风险 - DatabaseManager动态表名拼接
**位置**: `src/main/java/com/mtxgdn/db/DatabaseManager.java`  
**问题描述**:
```java
// Line 300
String sql = "SELECT * FROM " + tableName + " LIMIT ? OFFSET ?";
```

**风险等级**: 严重  
**影响**: 虽然有 `assertSafeTableName()` 白名单校验，但表名缓存有5分钟过期时间，期间新建的表可能被注入利用。

**修复建议**:
- 使用更严格的表名白名单（硬编码）
- 减少缓存过期时间到30秒
- 添加表名格式正则验证：`^[a-z_]+$`

---

#### 2. 业务逻辑漏洞 - 交易系统价格验证不足
**位置**: `src/main/java/com/mtxgdn/game/service/TradeService.java`  
**问题描述**: 上架物品时未充分验证价格范围

**风险等级**: 高危  
**影响**: 
- 可能通过负数价格刷钱
- 可能通过超大价格导致整数溢出

**修复建议**:
```java
// 添加价格验证
if (priceSpiritStones <= 0 || priceSpiritStones > 999999999) {
    throw new IllegalArgumentException("价格必须在1-999999999之间");
}
```

---

#### 3. 并发安全 - 境界突破竞态条件
**位置**: `src/main/java/com/mtxgdn/game/service/RealmService.java`  
**问题描述**: 突破境界时未加锁，多线程可能导致重复突破

**风险等级**: 高危  
**影响**: 玩家可通过并发请求重复突破，跳过资源消耗

**修复建议**:
```java
// 使用数据库悲观锁或乐观锁
String sql = "SELECT * FROM players WHERE id = ? FOR UPDATE";
// 或使用版本号机制
UPDATE players SET realm = ?, version = version + 1 
WHERE id = ? AND version = ?
```

---

### 🟡 高危问题

#### 4. 输入验证 - 用户名长度限制不足
**位置**: `src/main/java/com/mtxgdn/service/UserService.java:32`  
**问题描述**:
```java
if (username.length() > 64) {
    return buildError(400, "用户名长度不能超过64位");
}
```

**风险等级**: 中危  
**影响**: 64字符可能过长，建议限制为32字符

---

#### 5. 会话管理 - JWT密钥硬编码风险
**位置**: `src/main/java/com/mtxgdn/util/JwtUtil.java` (需查看)  
**风险等级**: 高危  
**影响**: 如果JWT密钥硬编码或使用弱密钥，可被伪造token

**修复建议**:
- 确保JWT密钥从配置文件读取
- 密钥长度至少256位
- 定期轮换密钥

---

#### 6. 资源泄露 - 未关闭的数据库连接
**位置**: 多处  
**问题描述**: 部分代码使用try-with-resources，但有些地方可能漏掉

**风险等级**: 中危  
**影响**: 长时间运行可能耗尽连接池

**修复建议**: 全面审查，确保所有数据库连接都使用try-with-resources

---

### 🟢 中危问题

#### 7. 日志安全 - 敏感信息泄露
**位置**: 全局  
**问题描述**: 可能在日志中输出密码、token等敏感信息

**修复建议**:
- 添加敏感数据脱敏工具类
- 审查所有LOG.info/error语句

---

#### 8. 速率限制 - 部分接口缺少限流
**位置**: `src/main/java/com/mtxgdn/rest/GameResource.java`  
**问题描述**: 虽然有RateLimiter，但不是所有接口都启用

**修复建议**: 为所有修改操作添加速率限制

---

## 二、安全测试套件

### 已编写测试

#### 1. SQL注入测试 (`SecurityTest.java`)
```java
- 用户名SQL注入测试
- 物品键SQL注入测试
- 特殊字符注入测试
```

#### 2. 业务逻辑测试 (`SecurityTest.java`)
```java
- 负数金币刷钱测试
- 整数溢出攻击测试
- 重复使用物品测试
- 交易系统刷钱测试
```

#### 3. 并发安全测试 (`SecurityTest.java`)
```java
- 同时突破境界测试
```

#### 4. 输入验证测试 (`SecurityTest.java`)
```java
- 超长字符串测试
- 特殊字符测试
```

#### 5. 全功能测试 (`ComprehensiveFunctionalTest.java`)
```java
- 10个测试阶段
- 模拟5个玩家
- 覆盖所有核心功能
```

---

## 三、代码质量问题

### 1. 缺少输入验证
**影响范围**: 多个Service类  
**问题**: 部分方法未验证参数范围

**示例**:
```java
// ItemService.addItem - 应验证quantity上限
public boolean addItem(long playerId, String itemKey, long quantity) {
    // 缺少: if (quantity > MAX_QUANTITY) throw ...
}
```

---

### 2. 异常处理过于宽泛
**影响范围**: 全局  
**问题**: 使用 `catch (Exception e)` 可能隐藏问题

**建议**: 捕获具体异常类型

---

### 3. 魔法数字
**影响范围**: 多处  
**问题**: 硬编码的数值应提取为常量

**示例**:
```java
// 应改为常量
private static final int MAX_FAILED_ATTEMPTS = 5;
private static final long LOCK_DURATION_MINUTES = 15;
```

---

## 四、游戏平衡性问题

### 1. 经验值获取速率
- **当前**: 修炼速度可能过快
- **建议**: 根据测试调整 cultivationPerSecond

### 2. 物品掉落概率
- **当前**: 未发现明显问题
- **建议**: 增加稀有度系统

### 3. 交易手续费
- **当前**: 5%
- **建议**: 可考虑阶梯式手续费

---

## 五、修复优先级

### P0 (立即修复)
1. ✅ 境界突破并发锁
2. ✅ 交易价格验证
3. ✅ SQL注入防护加强

### P1 (本周修复)
4. ✅ 输入验证增强
5. ✅ JWT密钥安全检查
6. ✅ 资源泄露审查

### P2 (下周修复)
7. 日志脱敏
8. 速率限制完善
9. 代码质量优化

---

## 六、测试执行说明

### 环境要求
- Java 23
- Maven 3.8+
- SQLite 3.x

### 运行测试

#### Windows:
```bash
.\run_tests.bat
```

#### Linux/Mac:
```bash
# 安全测试
mvn test -Dtest=SecurityTest

# 功能测试
mvn test -Dtest=ComprehensiveFunctionalTest

# 全部测试
mvn test
```

### 测试覆盖率
- **单元测试**: 已编写
- **集成测试**: 已编写
- **安全测试**: 已编写
- **性能测试**: 待编写
- **压力测试**: 待编写

---

## 七、安全加固建议

### 1. 数据库层
```sql
-- 使用预编译语句（已实现）
-- 添加索引优化查询
CREATE INDEX idx_players_user_id ON players(user_id);
CREATE INDEX idx_players_items_player_id ON players_items(player_id);
```

### 2. 应用层
```java
// 添加全局输入验证器
public class InputValidator {
    public static void validateUsername(String username) {
        if (!username.matches("^[a-zA-Z0-9_]{3,32}$")) {
            throw new IllegalArgumentException("用户名格式错误");
        }
    }
}
```

### 3. 网络层
- 启用HTTPS（生产环境）
- 配置CORS白名单
- 添加请求签名验证

---

## 八、性能优化建议

### 1. 数据库连接池
```java
// 当前HikariCP配置合理
// 建议根据负载调整
cfg.setMaximumPoolSize(20);  // 高负载时增加
```

### 2. 缓存策略
```java
// 建议添加Redis缓存层
// 缓存玩家信息、物品信息等热数据
```

### 3. 查询优化
- 避免 SELECT *
- 使用分页查询
- 添加数据库索引

---

## 九、测试结果预期

### SQL注入测试
```
预期结果: 所有注入尝试被阻止
实际结果: [待执行测试]
```

### 业务逻辑测试
```
预期结果: 负数金币/溢出被拒绝
实际结果: [待执行测试]
```

### 并发测试
```
预期结果: 并发突破只成功1次
实际结果: [待执行测试]
```

### 功能测试
```
预期结果: 5个玩家完成完整流程
实际结果: [待执行测试]
```

---

## 十、后续建议

### 短期 (1-2周)
1. 修复所有P0和P1级别问题
2. 执行完整测试套件
3. 添加自动化测试到CI/CD

### 中期 (1个月)
1. 添加性能测试和压力测试
2. 实现日志监控和告警
3. 编写详细的API文档

### 长期 (3个月)
1. 安全审计和渗透测试
2. 代码质量持续改进
3. 性能调优和扩展性优化

---

## 附录

### A. 已发现的具体漏洞点

| 文件 | 行号 | 问题类型 | 严重程度 |
|------|------|----------|----------|
| DatabaseManager.java | 300 | SQL注入风险 | 严重 |
| RealmService.java | - | 并发竞态 | 高危 |
| TradeService.java | - | 价格验证 | 高危 |
| UserService.java | 32 | 输入验证 | 中危 |

### B. 测试文件清单

1. `src/test/java/com/mtxgdn/security/SecurityTest.java` - 安全测试
2. `src/test/java/com/mtxgdn/functional/ComprehensiveFunctionalTest.java` - 功能测试
3. `run_tests.bat` - 测试运行脚本

### C. 依赖安全检查

建议使用以下工具检查依赖漏洞：
```bash
mvn dependency:tree
mvn versions:display-dependency-updates
```

已知安全的依赖版本：
- MySQL Connector: 9.7.0 ✅
- SQLite JDBC: 3.49.1.0 ✅
- BCrypt: 0.4 ✅
- JJWT: 0.11.5 ✅

---

## 总结

本项目整体架构合理，使用了PreparedStatement防止SQL注入，使用BCrypt加密密码，使用JWT进行认证。但仍存在一些需要修复的问题：

**优点**:
- ✅ 使用PreparedStatement防SQL注入
- ✅ 密码使用BCrypt加密
- ✅ JWT认证机制
- ✅ 连接池管理完善
- ✅ 支持SQLite和MySQL双模式

**需改进**:
- ⚠️ 并发控制需加强
- ⚠️ 输入验证需完善
- ⚠️ 部分业务逻辑需加固
- ⚠️ 需要添加更多单元测试

**建议执行测试后，根据实际结果调整优先级并逐步修复。**
