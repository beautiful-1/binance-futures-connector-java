package com.binance.connector.futures.client.utils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketConnection extends WebSocketListener {
    private static final AtomicInteger connectionCounter = new AtomicInteger(0);
    private static final int NORMAL_CLOSURE_STATUS = 1000;
    private static final Logger logger = LoggerFactory.getLogger(WebSocketConnection.class);
    
    // 重连配置
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY = 1000; // 1秒
    private static final long MAX_RECONNECT_DELAY = 300000;   // 5分钟
    private static final long HEARTBEAT_INTERVAL = 30000;    // 30秒心跳检测
    private static final long CONNECTION_TIMEOUT = 600000;   // 10分钟无消息超时（适应强平订单低频特性）

    // 原有的回调接口（保持向后兼容）
    private final WebSocketCallback onOpenCallback;
    private final WebSocketCallback onMessageCallback;
    private final WebSocketCallback onClosingCallback;
    private final WebSocketCallback onFailureCallback;
    
    // 新增的增强监听器
    private final EnhancedWebSocketListener enhancedListener;
    
    private final int connectionId;
    private final Request request;
    private final String streamName;
    private final OkHttpClient dedicatedClient; // 每个连接独立的客户端
    
    // 连接状态管理
    private final AtomicReference<EnhancedWebSocketListener.ConnectionState> connectionState = 
        new AtomicReference<>(EnhancedWebSocketListener.ConnectionState.DISCONNECTED);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicLong lastMessageTime = new AtomicLong(System.currentTimeMillis());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    private volatile WebSocket webSocket;
    private final Object mutex;

    /**
     * 原有构造函数（保持向后兼容）
     */
    public WebSocketConnection(
            WebSocketCallback onOpenCallback,
            WebSocketCallback onMessageCallback,
            WebSocketCallback onClosingCallback,
            WebSocketCallback onFailureCallback,
            Request request
    ) {
        this(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request, null);
    }
    
    /**
     * 增强构造函数，支持新的监听器接口
     */
    public WebSocketConnection(
            WebSocketCallback onOpenCallback,
            WebSocketCallback onMessageCallback,
            WebSocketCallback onClosingCallback,
            WebSocketCallback onFailureCallback,
            Request request,
            EnhancedWebSocketListener enhancedListener
    ) {
        this.onOpenCallback = onOpenCallback;
        this.onMessageCallback = onMessageCallback;
        this.onClosingCallback = onClosingCallback;
        this.onFailureCallback = onFailureCallback;
        this.enhancedListener = enhancedListener;
        this.connectionId = WebSocketConnection.connectionCounter.incrementAndGet();
        this.request = request;
        this.streamName = request.url().host() + request.url().encodedPath();
        this.dedicatedClient = HttpClientSingleton.createDedicatedWebSocketClient(); // 独立客户端
        this.webSocket = null;
        this.mutex = new Object();
        
        // 启动心跳检测
        startHeartbeatMonitor();
    }

    public void connect() {
        synchronized (mutex) {
            if (webSocket == null) {
                changeConnectionState(EnhancedWebSocketListener.ConnectionState.CONNECTING);
                logger.info("[Connection {}] Connecting to {}", connectionId, streamName);
                webSocket = dedicatedClient.newWebSocket(request, this);
            } else {
                logger.info("[Connection {}] is already connected to {}", connectionId, streamName);
            }
        }
    }

    public int getConnectionId() {
        return connectionId;
    }


    public void close() {
        close(false);
    }
    
    /**
     * 关闭连接
     * @param isReconnect 是否为重连操作
     */
    public void close(boolean isReconnect) {
        synchronized (mutex) {
            if (webSocket != null) {
                logger.info("[Connection {}] Closing connection to {} (reconnect: {})", connectionId, streamName, isReconnect);
                webSocket.close(NORMAL_CLOSURE_STATUS, null);
                webSocket = null;
            }
            
            if (!isReconnect) {
                // 完全关闭时清理所有资源
                changeConnectionState(EnhancedWebSocketListener.ConnectionState.DISCONNECTED);
                shutdown();
            }
        }
    }
    
    /**
     * 完全关闭连接并清理资源
     */
    public void shutdown() {
        scheduler.shutdown();
        HttpClientSingleton.shutdownClient(dedicatedClient);
        logger.info("[Connection {}] Resources cleaned up", connectionId);
    }

    @Override
    public void onOpen(WebSocket ws, Response response) {
        logger.info("[Connection {}] Connected to Server", connectionId);
        changeConnectionState(EnhancedWebSocketListener.ConnectionState.CONNECTED);
        
        // 重置重连计数器
        int previousAttempts = reconnectAttempts.getAndSet(0);
        if (previousAttempts > 0 && enhancedListener != null) {
            enhancedListener.onReconnected(previousAttempts);
        } else if (enhancedListener != null) {
            enhancedListener.onConnected();
        }
        
        // 更新最后消息时间
        lastMessageTime.set(System.currentTimeMillis());
        
        // 调用原有回调（向后兼容）
        onOpenCallback.onReceive(null);
    }

    @Override
    public void onClosing(WebSocket ws, int code, String reason) {
        super.onClosing(ws, code, reason);
        logger.info("[Connection {}] Connection closing: {} - {}", connectionId, code, reason);
        
        if (enhancedListener != null) {
            enhancedListener.onDisconnected(code, reason);
        }
        
        onClosingCallback.onReceive(reason);
        
        // 如果不是正常关闭，则触发重连
        if (code != NORMAL_CLOSURE_STATUS) {
            changeConnectionState(EnhancedWebSocketListener.ConnectionState.RECONNECTING);
            scheduleReconnect();
        }
    }

    @Override
    public void onMessage(WebSocket ws, String text) {
        // 更新最后消息时间
        lastMessageTime.set(System.currentTimeMillis());
        
        // 调用监听器
        if (enhancedListener != null) {
            enhancedListener.onMessage(text);
        }
        onMessageCallback.onReceive(text);
    }

    @Override
    public void onFailure(WebSocket ws, Throwable t, Response response) {
        logger.error("[Connection {}] Failure", connectionId, t);
        changeConnectionState(EnhancedWebSocketListener.ConnectionState.FAILED);
        
        String responseBody = response != null ? response.toString() : null;
        if (enhancedListener != null) {
            enhancedListener.onError(t, responseBody);
        }
        
        onFailureCallback.onReceive(null);
        
        // 检查是否需要重连
        if (shouldReconnect(t)) {
            changeConnectionState(EnhancedWebSocketListener.ConnectionState.RECONNECTING);
            scheduleReconnect();
        }
    }
    
    /**
     * 更改连接状态并通知监听器
     */
    private void changeConnectionState(EnhancedWebSocketListener.ConnectionState newState) {
        EnhancedWebSocketListener.ConnectionState oldState = connectionState.getAndSet(newState);
        if (oldState != newState && enhancedListener != null) {
            enhancedListener.onConnectionStateChanged(newState);
        }
    }
    
    /**
     * 判断是否需要重连
     */
    private boolean shouldReconnect(Throwable error) {
        if (reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
            return false;
        }
        
        // 检查错误类型
        String errorMessage = error.getMessage();
        String errorClass = error.getClass().getSimpleName();
        
        return errorClass.contains("EOFException") ||
               errorClass.contains("SocketException") ||
               errorClass.contains("ConnectException") ||
               errorClass.contains("SocketTimeoutException") ||
               errorClass.contains("InterruptedIOException") ||
               (errorMessage != null && (
                   errorMessage.contains("Connection reset") ||
                   errorMessage.contains("Connection refused") ||
                   errorMessage.contains("Connection timed out") ||
                   errorMessage.contains("executor rejected") ||
                   errorMessage.contains("Broken pipe")
               ));
    }
    
    /**
     * 安排重连任务
     */
    private void scheduleReconnect() {
        int attempts = reconnectAttempts.incrementAndGet();
        
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            logger.error("[Connection {}] Max reconnect attempts ({}) reached, giving up", connectionId, MAX_RECONNECT_ATTEMPTS);
            changeConnectionState(EnhancedWebSocketListener.ConnectionState.FAILED);
            if (enhancedListener != null) {
                enhancedListener.onReconnectFailed(MAX_RECONNECT_ATTEMPTS);
            }
            return;
        }
        
        long delay = calculateReconnectDelay(attempts);
        logger.info("[Connection {}] Scheduling reconnect attempt {} in {}ms", connectionId, attempts, delay);
        
        if (enhancedListener != null) {
            enhancedListener.onReconnecting(attempts);
        }
        
        scheduler.schedule(() -> {
            synchronized (mutex) {
                if (connectionState.get() == EnhancedWebSocketListener.ConnectionState.RECONNECTING) {
                    logger.info("[Connection {}] Executing reconnect attempt {}", connectionId, attempts);
                    
                    // 关闭旧连接
                    if (webSocket != null) {
                        webSocket.close(NORMAL_CLOSURE_STATUS, "Reconnecting");
                        webSocket = null;
                    }
                    
                    // 等待一下让资源清理
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    
                    // 创建新连接
                    changeConnectionState(EnhancedWebSocketListener.ConnectionState.CONNECTING);
                    webSocket = dedicatedClient.newWebSocket(request, this);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 计算重连延迟时间（指数退避）
     */
    private long calculateReconnectDelay(int attempts) {
        if (attempts <= 1) {
            return INITIAL_RECONNECT_DELAY;
        }
        
        // 指数退避: delay = initial_delay * 2^(attempts-1)
        long delay = INITIAL_RECONNECT_DELAY * (1L << Math.min(attempts - 1, 8)); // 最多2^8=256倍
        return Math.min(delay, MAX_RECONNECT_DELAY);
    }
    
    /**
     * 启动心跳监控
     */
    private void startHeartbeatMonitor() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (connectionState.get() == EnhancedWebSocketListener.ConnectionState.CONNECTED) {
                long timeSinceLastMessage = System.currentTimeMillis() - lastMessageTime.get();
                
                if (timeSinceLastMessage > CONNECTION_TIMEOUT) {
                    logger.warn("[Connection {}] Heartbeat timeout: {}ms since last message, triggering reconnect", 
                        connectionId, timeSinceLastMessage);
                    
                    changeConnectionState(EnhancedWebSocketListener.ConnectionState.RECONNECTING);
                    scheduleReconnect();
                }
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 获取当前连接状态
     */
    public EnhancedWebSocketListener.ConnectionState getConnectionState() {
        return connectionState.get();
    }
    
    /**
     * 获取重连尝试次数
     */
    public int getReconnectAttempts() {
        return reconnectAttempts.get();
    }
    
    /**
     * 手动触发重连
     */
    public void reconnect() {
        logger.info("[Connection {}] Manual reconnect triggered", connectionId);
        reconnectAttempts.set(0); // 重置计数器
        changeConnectionState(EnhancedWebSocketListener.ConnectionState.RECONNECTING);
        scheduleReconnect();
    }
}
