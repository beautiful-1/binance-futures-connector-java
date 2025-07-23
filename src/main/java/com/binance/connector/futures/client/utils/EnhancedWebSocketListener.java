package com.binance.connector.futures.client.utils;

/**
 * 增强的WebSocket事件监听器接口
 * 提供连接生命周期的完整事件回调
 */
public interface EnhancedWebSocketListener {
    
    /**
     * 连接成功建立时调用
     */
    default void onConnected() {}
    
    /**
     * 接收到消息时调用
     * @param message 消息内容
     */
    void onMessage(String message);
    
    /**
     * 连接断开时调用
     * @param code 关闭代码
     * @param reason 关闭原因
     */
    default void onDisconnected(int code, String reason) {}
    
    /**
     * 连接发生错误时调用
     * @param error 错误信息
     * @param response HTTP响应（可能为null）
     */
    default void onError(Throwable error, String response) {}
    
    /**
     * 开始重连时调用
     * @param attempt 重连尝试次数（从1开始）
     */
    default void onReconnecting(int attempt) {}
    
    /**
     * 重连成功时调用
     * @param attempt 成功的重连尝试次数
     */
    default void onReconnected(int attempt) {}
    
    /**
     * 重连失败，放弃重连时调用
     * @param maxAttempts 最大重连尝试次数
     */
    default void onReconnectFailed(int maxAttempts) {}
    
    /**
     * 连接状态变化时调用
     * @param newState 新的连接状态
     */
    default void onConnectionStateChanged(ConnectionState newState) {}
    
    /**
     * 连接状态枚举
     */
    enum ConnectionState {
        DISCONNECTED,    // 未连接
        CONNECTING,      // 连接中
        CONNECTED,       // 已连接
        RECONNECTING,    // 重连中
        FAILED          // 连接失败
    }
}