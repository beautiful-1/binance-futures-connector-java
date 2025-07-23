# 增强版Binance WebSocket SDK使用示例

## 🎯 解决问题

此优化版本专门解决了以下问题：
1. **RejectedExecutionException: executor rejected** - OkHttp线程池共享导致的问题
2. **断网后无法重连** - 缺少智能重连机制
3. **连接状态不透明** - 无法获知真实连接状态
4. **资源泄露** - WebSocket连接资源未正确清理

## 🚀 核心改进

### 1. 每个连接独立的OkHttpClient
```java
// 旧版本：全局共享客户端（问题根源）
private static final OkHttpClient client = HttpClientSingleton.getHttpClient(); // 所有连接共享同一个线程池

// 新版本：每个连接独立的客户端
this.dedicatedClient = HttpClientSingleton.createDedicatedWebSocketClient(); // 独立线程池
```

### 2. 智能重连机制
- **指数退避**: 1秒 → 2秒 → 4秒 → ... 最大5分钟
- **异常识别**: 精确识别需要重连的异常类型
- **连接验证**: 心跳检测确保连接真正可用
- **资源清理**: 重连前完全清理旧资源

### 3. 增强的事件监听
```java
public interface EnhancedWebSocketListener {
    void onMessage(String message);           // 接收消息
    void onConnected();                       // 连接成功
    void onDisconnected(int code, String reason); // 连接断开
    void onError(Throwable error, String response); // 连接错误
    void onReconnecting(int attempt);         // 开始重连
    void onReconnected(int attempt);          // 重连成功
    void onReconnectFailed(int maxAttempts);  // 重连失败
    void onConnectionStateChanged(ConnectionState newState); // 状态变化
}
```

## 💡 使用方式

### 方式1：全局自动重连（推荐⭐）

**🎯 一键启用：所有WebSocket连接都自动重连**

```java
import com.binance.connector.futures.client.impl.UMWebsocketClientImpl;

public class UniversalReconnectService {
    
    public void startAllStreams() {
        // 创建客户端并启用全局自动重连
        UMWebsocketClientImpl client = new UMWebsocketClientImpl()
            .enableAutoReconnect(); // 🌟 所有连接都自动重连！
        
        // 现在所有WebSocket连接都具备自动重连能力
        
        // 1. 强平订单流 - 自动重连 ✅
        int forceOrderId = client.allForceOrderStream(this::handleForceOrder);
        
        // 2. K线数据流 - 自动重连 ✅  
        int klineId = client.klineStream("BTCUSDT", "1m", this::handleKline);
        
        // 3. 深度数据流 - 自动重连 ✅
        int depthId = client.depthStream("BTCUSDT", 10, this::handleDepth);
        
        // 4. 标记价格流 - 自动重连 ✅
        int markPriceId = client.allMarkPriceStream(3, this::handleMarkPrice);
        
        // 5. 用户数据流 - 自动重连 ✅
        int userDataId = client.listenUserStream("your-listen-key", this::handleUserData);
        
        System.out.println("🚀 已启动多个数据流，全部支持自动重连！");
        System.out.println("连接IDs: " + Arrays.asList(forceOrderId, klineId, depthId, markPriceId, userDataId));
        
        // 监控连接状态
        startConnectionMonitor(client);
    }
    
    private void startConnectionMonitor(UMWebsocketClientImpl client) {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                System.out.println("📊 活跃连接数: " + client.getActiveConnectionCount());
                System.out.println("🔄 连接状态: " + client.getAllConnectionStates());
            }
        }, 30000, 60000); // 30秒后开始，每分钟打印一次
    }
    
    private void handleForceOrder(String message) {
        System.out.println("💥 强平订单: " + message);
    }
    
    private void handleKline(String message) {
        System.out.println("📈 K线数据: " + message);
    }
    
    private void handleDepth(String message) {
        System.out.println("📊 深度数据: " + message);
    }
    
    private void handleMarkPrice(String message) {
        System.out.println("🏷️ 标记价格: " + message);
    }
    
    private void handleUserData(String message) {
        System.out.println("👤 用户数据: " + message);
    }
}
```

### 方式2：自定义重连监听器

```java
import com.binance.connector.futures.client.impl.UMWebsocketClientImpl;
import com.binance.connector.futures.client.utils.EnhancedWebSocketListener;

public class CustomReconnectService {
    
    public void startWithCustomListener() {
        // 自定义重连事件监听器
        EnhancedWebSocketListener customListener = new EnhancedWebSocketListener() {
            @Override
            public void onMessage(String message) {
                // 消息由原有回调处理，这里可以留空
            }

            @Override
            public void onConnected() {
                System.out.println("🟢 WebSocket连接建立成功");
                // 可以发送通知、更新UI状态等
            }

            @Override
            public void onDisconnected(int code, String reason) {
                System.out.println("🟡 WebSocket连接断开: " + code + " - " + reason);
                // 可以记录日志、发送警报等
            }

            @Override
            public void onError(Throwable error, String response) {
                System.err.println("🔴 WebSocket连接错误: " + error.getMessage());
                // 可以发送错误报告等
            }

            @Override
            public void onReconnecting(int attempt) {
                System.out.println("🔄 正在进行第" + attempt + "次重连尝试...");
                // 可以更新重连状态、显示进度等
            }

            @Override
            public void onReconnected(int attempt) {
                System.out.println("✅ 重连成功！共尝试了" + attempt + "次");
                // 可以发送成功通知、恢复业务逻辑等
            }

            @Override
            public void onReconnectFailed(int maxAttempts) {
                System.err.println("❌ 重连失败，已达到最大尝试次数: " + maxAttempts);
                // 可以发送失败警报、切换到备用连接等
            }

            @Override
            public void onConnectionStateChanged(ConnectionState newState) {
                System.out.println("🔄 连接状态变更为: " + newState);
                // 可以记录状态历史、更新监控面板等
            }
        };
        
        // 使用自定义监听器启用自动重连
        UMWebsocketClientImpl client = new UMWebsocketClientImpl()
            .enableAutoReconnect(customListener);
        
        // 所有连接都会使用自定义的重连监听器
        client.allForceOrderStream(this::handleMessage);
        client.klineStream("BTCUSDT", "1m", this::handleMessage);
    }
    
    private void handleMessage(String message) {
        System.out.println("收到消息: " + message);
    }
}
```

### 方式3：专用客户端（兼容旧版）

```java
import com.binance.connector.futures.client.utils.ReliableForceOrderClient;

public class ForceOrderSpecializedService {
    
    public void startMonitoring() {
        // 创建专门的强平订单客户端（向下兼容）
        ReliableForceOrderClient client = new ReliableForceOrderClient(
            this::handleForceOrderMessage,  // 消息处理
            this::handleConnectionStatus    // 连接状态变化
        );
        
        // 启动监听
        client.start();
        
        // 定期打印统计信息
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                System.out.println("连接统计: " + client.getStats());
            }
        }, 60000, 60000); // 每分钟打印一次
    }
    
    private void handleForceOrderMessage(String message) {
        System.out.println("收到强平订单: " + message);
    }
    
    private void handleConnectionStatus(String status) {
        if (status.equals("CONNECTED")) {
            System.out.println("✅ 强平订单流已连接");
        } else if (status.startsWith("RECONNECTING")) {
            System.out.println("🔄 正在重连...");
        } else if (status.startsWith("RECONNECTED")) {
            System.out.println("✅ 重连成功");
        }
    }
}
```

### 方式4：完全控制（高级用户）

```java
import com.binance.connector.futures.client.impl.UMWebsocketClientImpl;
import com.binance.connector.futures.client.utils.EnhancedWebSocketListener;

public class AdvancedForceOrderService {
    
    public void startAdvancedMonitoring() {
        UMWebsocketClientImpl client = new UMWebsocketClientImpl();
        
        EnhancedWebSocketListener listener = new EnhancedWebSocketListener() {
            @Override
            public void onMessage(String message) {
                // 处理消息
                processForceOrder(message);
            }
            
            @Override
            public void onConnected() {
                System.out.println("✅ WebSocket连接已建立");
            }
            
            @Override
            public void onDisconnected(int code, String reason) {
                System.out.println("❌ 连接断开: " + code + " - " + reason);
            }
            
            @Override
            public void onError(Throwable error, String response) {
                System.err.println("🚨 连接错误: " + error.getMessage());
            }
            
            @Override
            public void onReconnecting(int attempt) {
                System.out.println("🔄 第" + attempt + "次重连尝试...");
            }
            
            @Override
            public void onReconnected(int attempt) {
                System.out.println("✅ 重连成功（尝试了" + attempt + "次）");
            }
            
            @Override
            public void onReconnectFailed(int maxAttempts) {
                System.err.println("💥 重连失败，已达到最大尝试次数: " + maxAttempts);
            }
            
            @Override
            public void onConnectionStateChanged(ConnectionState newState) {
                System.out.println("🔄 连接状态: " + newState);
            }
        };
        
        // 使用增强的API
        int connectionId = client.allForceOrderStreamEnhanced(listener);
        System.out.println("连接ID: " + connectionId);
    }
    
    private void processForceOrder(String message) {
        // 具体的业务逻辑
        // ...
    }
}
```

## 📊 效果对比

### 问题解决前
```
[ERROR] java.util.concurrent.RejectedExecutionException: 
    Task rejected from ThreadPoolExecutor@5971c622[Terminated, pool size = 0]
    
❌ 断网后无法重连
❌ 线程池状态异常
❌ 资源泄露
❌ 连接状态不明
```

### 问题解决后
```
[INFO] [Connection 1] Connected to Server
[INFO] Force order stream connected successfully
[INFO] 收到强平订单: {"e":"forceOrder","E":1234567890...}
[WARN] [Connection 1] Connection closing: 1006 - Connection lost  
[INFO] [Connection 1] Scheduling reconnect attempt 1 in 1000ms
[INFO] [Connection 1] Executing reconnect attempt 1
[INFO] [Connection 2] Connected to Server
[INFO] Force order stream reconnected successfully after 1 attempts

✅ 自动重连成功
✅ 独立线程池
✅ 完整资源清理
✅ 透明连接状态
```

## 🔧 配置参数

可以通过修改`WebSocketConnection`类中的常量来调整行为：

```java
// 重连配置
private static final int MAX_RECONNECT_ATTEMPTS = 10;        // 最大重连次数
private static final long INITIAL_RECONNECT_DELAY = 1000;    // 初始重连延迟(1秒)
private static final long MAX_RECONNECT_DELAY = 300000;      // 最大重连延迟(5分钟)
private static final long HEARTBEAT_INTERVAL = 30000;       // 心跳检测间隔(30秒)
private static final long CONNECTION_TIMEOUT = 600000;      // 连接超时(10分钟，适应强平订单低频特性)
```

## 🎯 适用场景

此优化版本特别适合：
1. **生产环境** - 需要7x24小时稳定运行
2. **网络不稳定** - 经常出现断网重连的环境
3. **强平监控** - 需要实时监控市场强平订单
4. **资源敏感** - 需要精确控制资源使用

## 📝 注意事项

1. **向后兼容** - 所有原有API保持不变
2. **渐进升级** - 可以逐步迁移到新API
3. **资源管理** - 记得调用`shutdown()`清理资源
4. **日志监控** - 建议监控连接状态日志

## 📦 SDK打包和安装

### 1. 编译增强版SDK

#### Windows环境

```bash
# 切换到SDK目录
cd C:\project\binance-futures-connector-java

# 清理并编译（跳过代码检查和GPG签名）
# Windows PowerShell 用户请使用引号
mvn clean compile "-Dcheckstyle.skip=true" "-Dgpg.skip=true"

# 或者使用cmd（推荐）
mvn clean compile -Dcheckstyle.skip=true -Dgpg.skip=true

# 运行测试验证功能正常
mvn test "-Dcheckstyle.skip=true" "-Dgpg.skip=true"
```

#### Ubuntu/Linux环境

```bash
# 切换到SDK目录（假设项目在用户主目录下）
cd ~/binance-futures-connector-java

# 或者如果是在WSL中访问Windows项目
cd /mnt/c/project/binance-futures-connector-java

# 清理并编译（跳过代码检查和GPG签名）
mvn clean compile -Dcheckstyle.skip=true -Dgpg.skip=true

# 运行测试验证功能正常
mvn test -Dcheckstyle.skip=true -Dgpg.skip=true

# 检查Java和Maven版本（确保环境正确）
java -version
mvn -version
```

#### macOS环境

```bash
# 切换到SDK目录
cd ~/binance-futures-connector-java

# 清理并编译（跳过代码检查和GPG签名）
mvn clean compile -Dcheckstyle.skip=true -Dgpg.skip=true

# 运行测试验证功能正常
mvn test -Dcheckstyle.skip=true -Dgpg.skip=true
```

### 2. 安装到本地Maven仓库

#### Windows环境

```bash
# 安装增强版SDK到本地仓库
# Windows PowerShell 用户请使用引号
mvn clean install "-Dcheckstyle.skip=true" "-Dgpg.skip=true"

# 或者使用cmd（推荐）
mvn clean install -Dcheckstyle.skip=true -Dgpg.skip=true

# 验证安装成功（Windows PowerShell）
Get-ChildItem "$env:USERPROFILE\.m2\repository\io\github\binance\binance-futures-connector-java\3.0.6-enhanced-reconnect\"

# 验证安装成功（Windows cmd）
dir "%USERPROFILE%\.m2\repository\io\github\binance\binance-futures-connector-java\3.0.6-enhanced-reconnect\"
```

#### Ubuntu/Linux环境

```bash
# 安装增强版SDK到本地仓库
mvn clean install -Dcheckstyle.skip=true -Dgpg.skip=true

# 验证安装成功
ls ~/.m2/repository/io/github/binance/binance-futures-connector-java/3.0.6-enhanced-reconnect/

# 检查安装的文件详情
ls -la ~/.m2/repository/io/github/binance/binance-futures-connector-java/3.0.6-enhanced-reconnect/

# 如果遇到权限问题，可能需要调整.m2目录权限
sudo chown -R $USER:$USER ~/.m2/

# 清理Maven缓存（如果需要）
mvn dependency:purge-local-repository -DmanualInclude="io.github.binance:binance-futures-connector-java"
```

#### macOS环境

```bash
# 安装增强版SDK到本地仓库
mvn clean install -Dcheckstyle.skip=true -Dgpg.skip=true

# 验证安装成功
ls ~/.m2/repository/io/github/binance/binance-futures-connector-java/3.0.6-enhanced-reconnect/

# 检查安装的文件详情
ls -la ~/.m2/repository/io/github/binance/binance-futures-connector-java/3.0.6-enhanced-reconnect/
```

**安装成功后会看到以下文件:**
```
binance-futures-connector-java-3.0.6-enhanced-reconnect.jar
binance-futures-connector-java-3.0.6-enhanced-reconnect.pom
binance-futures-connector-java-3.0.6-enhanced-reconnect-sources.jar
binance-futures-connector-java-3.0.6-enhanced-reconnect-javadoc.jar
```

### 3. 在项目中使用增强版SDK

#### 方法一：更新依赖版本（推荐）

在项目的 `pom.xml` 中更新版本号：

```xml
<dependency>
    <groupId>io.github.binance</groupId>
    <artifactId>binance-futures-connector-java</artifactId>
    <version>3.0.6-enhanced-reconnect</version>
</dependency>
```

#### 方法二：父级pom管理（适用于多模块项目）

在父级 `pom.xml` 的 `dependencyManagement` 中：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.binance</groupId>
            <artifactId>binance-futures-connector-java</artifactId>
            <version>3.0.6-enhanced-reconnect</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

然后在子模块中直接引用：

```xml
<dependency>
    <groupId>io.github.binance</groupId>
    <artifactId>binance-futures-connector-java</artifactId>
</dependency>
```

### 4. 验证增强版功能

#### 编译验证

**Windows环境:**
```bash
# 在使用增强版SDK的项目目录中
cd C:\project\my-red-alert\kline-system
mvn clean compile

# 确认编译成功，没有依赖冲突
```

**Ubuntu/Linux环境:**
```bash
# 在使用增强版SDK的项目目录中（本地项目）
cd ~/my-red-alert/kline-system
mvn clean compile

# 或者在WSL中访问Windows项目
cd /mnt/c/project/my-red-alert/kline-system
mvn clean compile

# 确认编译成功，没有依赖冲突
echo "编译状态: $?"  # 0表示成功
```

**macOS环境:**
```bash
# 在使用增强版SDK的项目目录中
cd ~/my-red-alert/kline-system
mvn clean compile

# 确认编译成功，没有依赖冲突
```

#### 功能验证
```java
// 验证新增的类是否可用
import com.binance.connector.futures.client.utils.ReliableForceOrderClient;
import com.binance.connector.futures.client.utils.EnhancedWebSocketListener;

// 创建客户端测试
ReliableForceOrderClient client = new ReliableForceOrderClient(
    message -> System.out.println("收到消息: " + message),
    status -> System.out.println("状态变化: " + status)
);
```

### 5. 常见问题解决

#### 问题1：PowerShell参数解析错误
```bash
# 错误示例
mvn clean install -Dcheckstyle.skip=true -Dgpg.skip=true
# [ERROR] Unknown lifecycle phase ".skip=true"

# 解决方案：在PowerShell中使用引号
mvn clean install "-Dcheckstyle.skip=true" "-Dgpg.skip=true"

# 或者切换到cmd使用
cmd /c "mvn clean install -Dcheckstyle.skip=true -Dgpg.skip=true"
```

#### 问题2：编译时提示checkstyle错误
```bash
# 解决方案：跳过代码风格检查
mvn clean install "-Dcheckstyle.skip=true"
```

#### 问题3：GPG签名错误
```bash
# 解决方案：跳过GPG签名
mvn clean install "-Dgpg.skip=true"
```

#### 问题4：同时出现多个问题
```bash
# 解决方案：同时跳过两个检查（PowerShell）
mvn clean install "-Dcheckstyle.skip=true" "-Dgpg.skip=true"

# 解决方案：同时跳过两个检查（cmd）
mvn clean install -Dcheckstyle.skip=true -Dgpg.skip=true
```

#### 问题5：依赖冲突
```bash
# 清理本地仓库中的旧版本（Windows cmd）
rmdir /s /q "%USERPROFILE%\.m2\repository\io\github\binance\binance-futures-connector-java\3.0.5"

# 清理本地仓库中的旧版本（Windows PowerShell）
Remove-Item -Recurse -Force "$env:USERPROFILE\.m2\repository\io\github\binance\binance-futures-connector-java\3.0.5"

# 清理本地仓库中的旧版本（Ubuntu/Linux/macOS）
rm -rf ~/.m2/repository/io/github/binance/binance-futures-connector-java/3.0.5/

# 重新安装增强版（Windows PowerShell）
mvn clean install "-Dcheckstyle.skip=true" "-Dgpg.skip=true"

# 重新安装增强版（Ubuntu/Linux/macOS）
mvn clean install -Dcheckstyle.skip=true -Dgpg.skip=true
```

#### 问题6：Ubuntu/Linux环境特有问题

```bash
# 问题：Java版本不兼容
# 解决方案：检查和切换Java版本
java -version
update-alternatives --list java
sudo update-alternatives --config java

# 问题：Maven未安装或版本过低
# 解决方案：安装/更新Maven
sudo apt update
sudo apt install maven

# 检查Maven版本（要求3.6+）
mvn -version

# 问题：权限不足
# 解决方案：修复.m2目录权限
sudo chown -R $USER:$USER ~/.m2/
chmod -R 755 ~/.m2/

# 问题：网络连接问题（中国大陆用户）
# 解决方案：配置Maven镜像
mkdir -p ~/.m2
cat > ~/.m2/settings.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <name>Aliyun Maven</name>
      <url>https://maven.aliyun.com/repository/public</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
EOF

# 问题：WSL中文件系统权限问题
# 解决方案：在WSL中操作Windows项目时
cd /mnt/c/project/binance-futures-connector-java
# 确保有执行权限
chmod +x mvnw
# 使用Maven Wrapper
./mvnw clean install -Dcheckstyle.skip=true -Dgpg.skip=true
```

### 6. 环境特定注意事项

#### Windows环境
- **PowerShell**: 记得给 `-D` 参数加引号 `"-Dcheckstyle.skip=true"`
- **路径分隔符**: 使用反斜杠 `\` 或正斜杠 `/` 都可以
- **权限**: 通常不需要特殊权限设置

#### Ubuntu/Linux环境  
- **包管理**: 使用 `apt` 安装Java和Maven依赖
- **权限**: 注意 `.m2` 目录的权限设置
- **网络**: 大陆用户建议配置阿里云Maven镜像
- **WSL支持**: 可通过 `/mnt/c/` 访问Windows项目

#### macOS环境
- **包管理**: 可使用 `brew` 安装Java和Maven
- **路径**: 使用Unix风格路径 `~/`
- **权限**: 类似Linux，注意用户权限

### 7. 版本管理建议

#### 开发环境
- 使用增强版本 `3.0.6-enhanced-reconnect`
- 享受自动重连和稳定性改进
- **Windows**: 适合开发调试
- **Ubuntu**: 适合容器化部署测试
- **macOS**: 适合本地开发环境

#### 生产环境
- 充分测试后再部署增强版本
- 监控连接状态和重连统计
- 建议保留原版本作为回退选项
- **Linux服务器**: 推荐的生产环境
- **Docker容器**: 轻量化部署方案

#### 版本升级路径
```bash
# 1. 原版本 (存在问题)
3.0.5

# 2. 增强版本 (解决问题)
3.0.6-enhanced-reconnect

# 3. 未来官方版本 (可能整合改进)
3.0.7+ (官方发布时考虑升级)
```

### 8. 跨平台部署建议

#### 开发流程
```bash
# 1. Windows开发环境编译
mvn clean install "-Dcheckstyle.skip=true" "-Dgpg.skip=true"

# 2. Linux服务器部署
scp -r target/ user@server:/opt/app/
ssh user@server "cd /opt/app && mvn clean install -Dcheckstyle.skip=true -Dgpg.skip=true"

# 3. Docker容器化
docker build -t binance-sdk:3.0.6-enhanced .
docker run -d binance-sdk:3.0.6-enhanced
```

#### CI/CD集成
```yaml
# GitHub Actions示例
name: Build Enhanced SDK
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v2
    - name: Set up JDK 8
      uses: actions/setup-java@v2
      with:
        java-version: '8'
        distribution: 'adopt'
    - name: Build with Maven
      run: mvn clean install -Dcheckstyle.skip=true -Dgpg.skip=true
```

## 🔗 相关类

- `HttpClientSingleton` - HTTP客户端管理（支持独立实例）
- `EnhancedWebSocketListener` - 增强的事件监听器接口
- `WebSocketConnection` - 核心连接类（增加重连和状态管理）
- `ReliableForceOrderClient` - 简化的适配器类
- `UMWebsocketClientImpl` - 增强的WebSocket客户端实现