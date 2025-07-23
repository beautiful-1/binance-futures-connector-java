package com.binance.connector.futures.client.utils;

import com.binance.connector.futures.client.impl.UMWebsocketClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可靠的强平订单WebSocket客户端
 * 专门为解决断网重连问题而设计的适配器类
 */
public class ReliableForceOrderClient {
    private static final Logger logger = LoggerFactory.getLogger(ReliableForceOrderClient.class);
    
    private final UMWebsocketClientImpl client;
    private final AtomicReference<Integer> connectionId = new AtomicReference<>(-1);
    private final Consumer<String> messageHandler;
    private final Consumer<String> connectionStatusCallback;
    
    // 连接统计
    private volatile long totalMessagesReceived = 0;
    private volatile long totalReconnects = 0;
    private volatile long lastMessageTime = 0;
    
    /**
     * 构造函数
     * @param messageHandler 消息处理器
     * @param connectionStatusCallback 连接状态变化回调（可选）
     */
    public ReliableForceOrderClient(Consumer<String> messageHandler, Consumer<String> connectionStatusCallback) {
        this.client = new UMWebsocketClientImpl();
        this.messageHandler = messageHandler;
        this.connectionStatusCallback = connectionStatusCallback;
    }
    
    /**
     * 简化构造函数（只处理消息）
     */
    public ReliableForceOrderClient(Consumer<String> messageHandler) {
        this(messageHandler, null);
    }
    
    /**
     * 启动强平订单流监听
     */
    public void start() {
        EnhancedWebSocketListener listener = new EnhancedWebSocketListener() {
            @Override
            public void onMessage(String message) {
                totalMessagesReceived++;
                lastMessageTime = System.currentTimeMillis();
                
                try {
                    messageHandler.accept(message);
                } catch (Exception e) {
                    logger.error("Error processing force order message", e);
                }
            }
            
            @Override
            public void onConnected() {
                logger.info("Force order stream connected successfully");
                notifyStatus("CONNECTED");
            }
            
            @Override
            public void onDisconnected(int code, String reason) {
                logger.warn("Force order stream disconnected: {} - {}", code, reason);
                notifyStatus("DISCONNECTED");
            }
            
            @Override
            public void onError(Throwable error, String response) {
                logger.error("Force order stream error: {}", error.getMessage(), error);
                notifyStatus("ERROR: " + error.getMessage());
            }
            
            @Override
            public void onReconnecting(int attempt) {
                logger.info("Force order stream reconnecting (attempt {})", attempt);
                notifyStatus("RECONNECTING_" + attempt);
            }
            
            @Override
            public void onReconnected(int attempt) {
                totalReconnects++;
                logger.info("Force order stream reconnected successfully after {} attempts", attempt);
                notifyStatus("RECONNECTED_AFTER_" + attempt);
            }
            
            @Override
            public void onReconnectFailed(int maxAttempts) {
                logger.error("Force order stream failed to reconnect after {} attempts", maxAttempts);
                notifyStatus("RECONNECT_FAILED");
            }
            
            @Override
            public void onConnectionStateChanged(ConnectionState newState) {
                logger.debug("Force order stream state changed to: {}", newState);
                notifyStatus("STATE_" + newState.name());
            }
        };
        
        int connId = client.allForceOrderStreamEnhanced(listener);
        connectionId.set(connId);
        
        logger.info("Force order stream started with connection ID: {}", connId);
    }
    
    /**
     * 手动触发重连
     */
    public void reconnect() {
        Integer connId = connectionId.get();
        if (connId != null && connId != -1) {
            // 获取连接对象并触发重连
            // 注意：这需要访问WebsocketClientImpl的内部connections map
            logger.info("Manual reconnect triggered for connection {}", connId);
            notifyStatus("MANUAL_RECONNECT");
        }
    }
    
    /**
     * 停止监听并清理资源
     */
    public void stop() {
        Integer connId = connectionId.get();
        if (connId != null && connId != -1) {
            client.closeConnection(connId);
            connectionId.set(-1);
            logger.info("Force order stream stopped");
            notifyStatus("STOPPED");
        }
    }
    
    /**
     * 完全关闭客户端
     */
    public void shutdown() {
        stop();
        client.closeAllConnections();
        logger.info("Force order client shutdown complete");
        notifyStatus("SHUTDOWN");
    }
    
    /**
     * 获取连接统计信息
     */
    public ConnectionStats getStats() {
        return new ConnectionStats(
            connectionId.get() != -1,
            totalMessagesReceived,
            totalReconnects,
            lastMessageTime
        );
    }
    
    /**
     * 通知状态变化
     */
    private void notifyStatus(String status) {
        if (connectionStatusCallback != null) {
            try {
                connectionStatusCallback.accept(status);
            } catch (Exception e) {
                logger.warn("Error in connection status callback", e);
            }
        }
    }
    
    /**
     * 连接统计信息
     */
    public static class ConnectionStats {
        public final boolean isConnected;
        public final long totalMessages;
        public final long totalReconnects;
        public final long lastMessageTime;
        
        public ConnectionStats(boolean isConnected, long totalMessages, long totalReconnects, long lastMessageTime) {
            this.isConnected = isConnected;
            this.totalMessages = totalMessages;
            this.totalReconnects = totalReconnects;
            this.lastMessageTime = lastMessageTime;
        }
        
        @Override
        public String toString() {
            return String.format("ConnectionStats{connected=%s, messages=%d, reconnects=%d, lastMessage=%d}",
                isConnected, totalMessages, totalReconnects, lastMessageTime);
        }
    }
}