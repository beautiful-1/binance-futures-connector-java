package com.binance.connector.futures.client.utils;

import okhttp3.OkHttpClient;
import okhttp3.Dispatcher;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

public final class HttpClientSingleton {
    private static OkHttpClient httpClient = null;

    private HttpClientSingleton() {
    }

    /**
     * 获取全局共享HTTP客户端（保持向后兼容）
     */
    public static OkHttpClient getHttpClient() {
        if (httpClient == null) {
            createHttpClient(null);
        }
        return httpClient;
    }

    public static OkHttpClient getHttpClient(ProxyAuth proxy) {
        if (httpClient == null) {
            createHttpClient(proxy);
        } else {
            verifyHttpClient(proxy);
        }
        return httpClient;
    }

    /**
     * 为WebSocket连接创建独立的OkHttpClient实例
     * 这是解决线程池共享问题的关键
     */
    public static OkHttpClient createDedicatedWebSocketClient() {
        return createDedicatedWebSocketClient(null);
    }

    /**
     * 为WebSocket连接创建独立的OkHttpClient实例（支持代理）
     */
    public static OkHttpClient createDedicatedWebSocketClient(ProxyAuth proxy) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            // 为每个实例创建独立的调度器和线程池
            .dispatcher(new Dispatcher())
            // WebSocket优化设置
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)    // WebSocket需要无限读取超时
            .writeTimeout(0, TimeUnit.MILLISECONDS)   // WebSocket需要无限写入超时
            .pingInterval(30, TimeUnit.SECONDS);      // 心跳检测
            
        if (proxy != null) {
            builder.proxy(proxy.getProxy());
            if (proxy.getAuth() != null) {
                builder.proxyAuthenticator(proxy.getAuth());
            }
        }
        
        return builder.build();
    }

    /**
     * 安全关闭OkHttpClient实例及其相关资源
     */
    public static void shutdownClient(OkHttpClient client) {
        if (client != null) {
            // 关闭调度器和线程池
            client.dispatcher().executorService().shutdown();
            
            // 清理连接池
            client.connectionPool().evictAll();
            
            // 等待线程池关闭
            try {
                if (!client.dispatcher().executorService().awaitTermination(5, TimeUnit.SECONDS)) {
                    client.dispatcher().executorService().shutdownNow();
                }
            } catch (InterruptedException e) {
                client.dispatcher().executorService().shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void createHttpClient(ProxyAuth proxy) {
        if (proxy == null) {
            httpClient = new OkHttpClient();
        } else {
            if (proxy.getAuth() == null) {
                httpClient = new OkHttpClient.Builder().proxy(proxy.getProxy()).build();
            } else {
                httpClient = new OkHttpClient.Builder().proxy(proxy.getProxy()).proxyAuthenticator(proxy.getAuth()).build();
            }
        }
    }

    private static void verifyHttpClient(ProxyAuth proxy) {
        Proxy prevProxy = httpClient.proxy();

        if ((proxy != null && !proxy.getProxy().equals(prevProxy)) || (proxy == null && prevProxy != null)) {
            createHttpClient(proxy);
        }
    }
}
