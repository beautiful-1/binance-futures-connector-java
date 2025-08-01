package com.binance.connector.futures.client.impl;

import com.binance.connector.futures.client.WebsocketClient;
import com.binance.connector.futures.client.utils.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <h2>Futures Websocket Streams</h2>
 * All stream endpoints under the
 * <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Connect"> USDⓈ-M Websocket Market Streams</a> and
 * <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Connect"> COIN-M Websocket Market Streams</a> and
 * <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/user-data-streams/Connect"> USDⓈ-M User Data Streams</a> and
 * <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/user-data-streams/Connect"> COIN-M User Data Streams</a>
 * section of the API documentation will be implemented in this class.
 * <br>
 * Response will be returned as callback.
 * 
 * <h3>增强功能</h3>
 * - 全局自动重连开关：所有WebSocket连接都支持自动重连
 * - 向后兼容：原有API保持不变
 * - 智能重连：指数退避策略，自动异常识别
 */
public abstract class WebsocketClientImpl implements WebsocketClient {
    private final String baseUrl;
    private final Map<Integer, WebSocketConnection> connections = new HashMap<>();
    private final WebSocketCallback noopCallback = msg -> {
    };
    private static final Logger logger = LoggerFactory.getLogger(WebsocketClientImpl.class);
    
    // 全局自动重连配置
    private volatile boolean autoReconnectEnabled = false;
    private EnhancedWebSocketListener defaultReconnectListener;

    public WebsocketClientImpl(String baseUrl) {
        this.baseUrl = baseUrl;
        // 初始化默认的重连监听器
        this.defaultReconnectListener = new EnhancedWebSocketListener() {
            @Override
            public void onMessage(String message) {
                // 默认不处理消息，由原有回调处理
            }

            @Override
            public void onConnected() {
                logger.info("[AutoReconnect] WebSocket connected successfully");
            }

            @Override
            public void onDisconnected(int code, String reason) {
                logger.warn("[AutoReconnect] WebSocket disconnected: {} - {}", code, reason);
            }

            @Override
            public void onError(Throwable error, String response) {
                logger.error("[AutoReconnect] WebSocket error: {}", error.getMessage(), error);
            }

            @Override
            public void onReconnecting(int attempt) {
                logger.info("[AutoReconnect] Attempting to reconnect (attempt {})", attempt);
            }

            @Override
            public void onReconnected(int attempt) {
                logger.info("[AutoReconnect] Reconnected successfully after {} attempts", attempt);
            }

            @Override
            public void onReconnectFailed(int maxAttempts) {
                logger.error("[AutoReconnect] Failed to reconnect after {} attempts", maxAttempts);
            }

            @Override
            public void onConnectionStateChanged(ConnectionState newState) {
                logger.debug("[AutoReconnect] Connection state changed to: {}", newState);
            }
        };
    }
    
    /**
     * 启用全局自动重连功能
     * 启用后，所有新建的WebSocket连接都会自动具备重连能力
     * 
     * @return this - 支持链式调用
     */
    public WebsocketClientImpl enableAutoReconnect() {
        this.autoReconnectEnabled = true;
        logger.info("Auto-reconnect feature enabled for all WebSocket connections");
        return this;
    }
    
    /**
     * 启用全局自动重连功能（使用自定义监听器）
     * 
     * @param customListener 自定义的重连事件监听器
     * @return this - 支持链式调用
     */
    public WebsocketClientImpl enableAutoReconnect(EnhancedWebSocketListener customListener) {
        this.autoReconnectEnabled = true;
        this.defaultReconnectListener = customListener;
        logger.info("Auto-reconnect feature enabled with custom listener");
        return this;
    }
    
    /**
     * 禁用全局自动重连功能
     * 禁用后，新建的WebSocket连接将使用传统的连接方式
     * 
     * @return this - 支持链式调用
     */
    public WebsocketClientImpl disableAutoReconnect() {
        this.autoReconnectEnabled = false;
        logger.info("Auto-reconnect feature disabled");
        return this;
    }
    
    /**
     * 检查自动重连功能是否已启用
     * 
     * @return true if auto-reconnect is enabled
     */
    public boolean isAutoReconnectEnabled() {
        return autoReconnectEnabled;
    }
    
    /**
     * 获取当前活跃的连接数量
     * 
     * @return 活跃连接数
     */
    public int getActiveConnectionCount() {
        return connections.size();
    }
    
     /**
     * 获取所有连接的状态信息
     * 
     * @return 连接状态映射
     */
    public Map<Integer, String> getAllConnectionStates() {
        Map<Integer, String> states = new HashMap<>();
        for (Map.Entry<Integer, WebSocketConnection> entry : connections.entrySet()) {
            WebSocketConnection connection = entry.getValue();
            states.put(entry.getKey(), connection.getConnectionState().toString());
        }
        return states;
    }
    
    /**
     * 手动触发指定连接的重连
     * 
     * @param connectionId 连接ID
     * @return true if reconnect was triggered successfully
     */
    public boolean triggerReconnect(int connectionId) {
        WebSocketConnection connection = connections.get(connectionId);
        if (connection != null) {
            try {
                connection.reconnect();
                logger.info("Manual reconnect triggered for connection {}", connectionId);
                return true;
            } catch (Exception e) {
                logger.error("Failed to trigger reconnect for connection {}", connectionId, e);
                return false;
            }
        } else {
            logger.warn("Connection {} not found, cannot trigger reconnect", connectionId);
            return false;
        }
    }

    public WebSocketCallback getNoopCallback() {
        return this.noopCallback;
    }

    public String getBaseUrl() {
        return this.baseUrl;
    }

    /**
     * The Aggregate Trade Streams push market trade information that is aggregated for fills with same price and taking side every 100 milliseconds.
     * Only market trades will be aggregated, which means the insurance fund trades and ADL trades won't be aggregated.
     * <br><br>
     * &lt;symbol&gt;@aggTrade
     * <br><br>
     * Update Speed: 100ms
     *
     * @param symbol trading symbol
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Aggregate-Trade-Streams">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Aggregate-Trade-Streams</a>
     */
    @Override
    public int aggTradeStream(String symbol, WebSocketCallback onMessageCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        return aggTradeStream(symbol, noopCallback,  onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #aggTradeStream(String, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param symbol trading symbol
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int aggTradeStream(String symbol, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@aggTrade", baseUrl, symbol.toLowerCase()));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * Mark price and funding rate for a single symbol pushed every 3 seconds or every second.
     * <br><br>
     * &lt;symbol&gt;@markPrice or &lt;symbol&gt;@markPrice@1s
     * <br><br>
     * Update Speed: 3000ms or 1000ms
     *
     * @param symbol trading symbol
     * @param speed speed in seconds, can be 1 or 3
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Mark-Price-Stream">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Mark-Price-Stream</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Mark-Price-Stream">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Mark-Price-Stream</a>
     */
    @Override
    public int markPriceStream(String symbol, int speed, WebSocketCallback onMessageCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        return markPriceStream(symbol, speed, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #markPriceStream(String, int, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param symbol trading symbol
     * @param speed speed in seconds, can be 1 or 3
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int markPriceStream(String symbol, int speed, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        Request request = null;
        final int defaultSpeed = 3;
        if (speed == defaultSpeed) {
            request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@markPrice", baseUrl, symbol.toLowerCase()));
        } else {
            request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@markPrice@%ss", baseUrl, symbol.toLowerCase(), speed));
        }
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * The Kline/Candlestick Stream push updates to the current klines/candlestick every 250 milliseconds (if existing).
     * <br><br>
     * &lt;symbol&gt;@kline_&lt;interval&gt;
     * <br><br>
     * Update Speed: 250ms
     *
     * @param symbol trading symbol
     * @param interval kline interval - 1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Kline-Candlestick-Streams">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Kline-Candlestick-Streams</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Kline-Candlestick-Streams">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Kline-Candlestick-Streams</a>
     */
    @Override
    public int klineStream(String symbol, String interval, WebSocketCallback onMessageCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        return klineStream(symbol, interval, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #klineStream(String, String, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param symbol trading symbol
     * @param interval kline interval - 1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int klineStream(String symbol, String interval, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@kline_%s", baseUrl, symbol.toLowerCase(), interval));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * The Kline/Candlestick Stream push updates to the current klines/candlestick every 250 milliseconds (if existing). Contract Types are: perpetual, current_quarter, next_quarter
     * <br><br>
     *  &lt;pair&gt;_&lt;contractType&gt;@continuousKline_&lt;interval&gt;
     * <br><br>
     * Update Speed: 250ms
     *
     * @param pair trading pair
     * @param contractType perpetual, current_quarter, next_quarter
     * @param interval kline interval - 1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Continuous-Contract-Kline-Candlestick-Streams">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Continuous-Contract-Kline-Candlestick-Streams</a>
     */
    @Override
    public int continuousKlineStream(String pair, String contractType, String interval, WebSocketCallback onMessageCallback) {
        ParameterChecker.checkParameterType(pair, String.class, "pair");
        return continuousKlineStream(pair, contractType, interval, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #continuousKlineStream(String, String, String, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param pair trading pair
     * @param interval kline interval - 1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M
     * @param contractType perpetual, current_quarter, next_quarter
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int continuousKlineStream(String pair, String contractType, String interval, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(pair, String.class, "pair");
        ParameterChecker.checkParameterType(contractType, String.class, "contractType");
        ParameterChecker.checkParameterType(interval, String.class, "interval");

        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s_%s@continuousKline_%s", baseUrl, pair.toLowerCase(), contractType, interval));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * 24hr rolling window mini-ticker statistics.
     * These are NOT the statistics of the UTC day, but a 24hr rolling window for the previous 24hrs.
     * <br><br>
     * &lt;symbol&gt;@miniTicker
     * <br><br>
     * Update Speed: 500ms
     *
     * @param symbol trading symbol
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Individual-Symbol-Mini-Ticker-Stream">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Individual-Symbol-Mini-Ticker-Stream</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Individual-Symbol-Mini-Ticker-Stream">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Individual-Symbol-Mini-Ticker-Stream</a>
     */
    @Override
    public int miniTickerStream(String symbol, WebSocketCallback onMessageCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        return miniTickerStream(symbol, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #miniTickerStream(String, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param symbol trading symbol
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int miniTickerStream(String symbol, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@miniTicker", baseUrl, symbol.toLowerCase()));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * 24hr rolling window mini-ticker statistics for all symbols that changed in an array.
     * These are NOT the statistics of the UTC day, but a 24hr rolling window for the previous 24hrs.
     * Note that only tickers that have changed will be present in the array.
     * <br><br>
     * !miniTicker@arr
     * <br><br>
     * Update Speed: 1000ms
     *
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/All-Market-Mini-Tickers-Stream">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/All-Market-Mini-Tickers-Stream</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/All-Market-Mini-Tickers-Stream">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/All-Market-Mini-Tickers-Stream</a>
     */
    @Override
    public int allMiniTickerStream(WebSocketCallback onMessageCallback) {
        return allMiniTickerStream(noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #allMiniTickerStream(WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int allMiniTickerStream(WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/!miniTicker@arr", baseUrl));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * 24hr rolling window ticker statistics for a single symbol.
     * These are NOT the statistics of the UTC day, but a 24hr rolling window for the previous 24hrs.
     * <br><br>
     * &lt;symbol&gt;@ticker
     * <br><br>
     * Update Speed: 500ms
     *
     * @param symbol trading symbol
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Individual-Symbol-Ticker-Streams">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Individual-Symbol-Ticker-Streams</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Individual-Symbol-Ticker-Streams">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Individual-Symbol-Ticker-Streams</a>
     */
    @Override
    public int symbolTicker(String symbol, WebSocketCallback onMessageCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        return symbolTicker(symbol, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #symbolTicker(String, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param symbol trading symbol
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int symbolTicker(String symbol, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@ticker", baseUrl, symbol.toLowerCase()));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * 24hr rolling window ticker statistics for all symbols.
     * These are NOT the statistics of the UTC day, but a 24hr rolling window from requestTime to 24hrs before.
     * Note that only tickers that have changed will be present in the array.
     * <br><br>
     * !ticker@arr
     * <br><br>
     * Update Speed: 1000ms
     *
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/All-Market-Tickers-Streams">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/All-Market-Tickers-Streams</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/All-Market-Tickers-Streams">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/All-Market-Tickers-Streams</a>
     */
    @Override
    public int allTickerStream(WebSocketCallback onMessageCallback) {
        return allTickerStream(noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #allTickerStream(WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int allTickerStream(WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/!ticker@arr", baseUrl));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

     /**
     * Pushes any update to the best bid or ask's price or quantity in real-time for a specified symbol.
     * <br><br>
     * &lt;symbol&gt;@bookTicker
     * <br><br>
     * Update Speed: Real-time
     *
     * @param symbol trading symbol
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Individual-Symbol-Book-Ticker-Streams">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Individual-Symbol-Book-Ticker-Streams</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Individual-Symbol-Book-Ticker-Streams">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Individual-Symbol-Book-Ticker-Streams</a>
     */
    @Override
    public int bookTicker(String symbol, WebSocketCallback onMessageCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        return bookTicker(symbol, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #bookTicker(String, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param symbol trading symbol
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int bookTicker(String symbol, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@bookTicker", baseUrl, symbol.toLowerCase()));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * Pushes any update to the best bid or ask's price or quantity in real-time for all symbols.
     * <br><br>
     * !bookTicker
     * <br><br>
     * Update Speed: Real-time
     *
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/All-Book-Tickers-Stream">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/All-Book-Tickers-Stream</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/All-Book-Tickers-Stream">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/All-Book-Tickers-Stream</a>
     */
    @Override
    public int allBookTickerStream(WebSocketCallback onMessageCallback) {
        return allBookTickerStream(noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #allBookTickerStream(WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int allBookTickerStream(WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/!bookTicker", baseUrl));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * The Liquidation Order Snapshot Streams push force liquidation order information for specific symbol.
     * For each symbol，only the latest one liquidation order within 1000ms will be pushed as the snapshot.
     * If no liquidation happens in the interval of 1000ms, no stream will be pushed.
     * <br><br>
     * &lt;symbol&gt;@forceOrder
     * <br><br>
     * Update Speed: 1000ms
     *
     * @param symbol trading symbol
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Liquidation-Order-Streams">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Liquidation-Order-Streams</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Liquidation-Order-Streams">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Liquidation-Order-Streams</a>
     */
    @Override
    public int forceOrderStream(String symbol, WebSocketCallback onMessageCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        return forceOrderStream(symbol, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #forceOrderStream(String, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param symbol trading symbol
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int forceOrderStream(String symbol, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@forceOrder", baseUrl, symbol.toLowerCase()));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * The All Liquidation Order Snapshot Streams push force liquidation order information for all symbols in the market.
     * For each symbol，only the latest one liquidation order within 1000ms will be pushed as the snapshot.
     * If no liquidation happens in the interval of 1000ms, no stream will be pushed.
     * <br><br>
     * !forceOrder@arr
     * <br><br>
     * Update Speed: 1000ms
     *
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/All-Market-Liquidation-Order-Streams">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/All-Market-Liquidation-Order-Streams</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/All-Market-Liquidation-Order-Streams">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/All-Market-Liquidation-Order-Streams</a>
     */
    @Override
    public int allForceOrderStream(WebSocketCallback onMessageCallback) {
        return allForceOrderStream(noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #allForceOrderStream(WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int allForceOrderStream(WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/!forceOrder@arr", baseUrl));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }
    
    /**
     * 增强的全市场强平订单流（带重连和状态管理）
     * 这是专门为解决断网重连问题而设计的API
     * 
     * @param enhancedListener 增强的事件监听器
     * @return int - Connection ID
     */
    public int allForceOrderStreamEnhanced(EnhancedWebSocketListener enhancedListener) {
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/!forceOrder@arr", baseUrl));
        
        // 使用空的原有回调保持兼容
        return createEnhancedConnection(
            msg -> {}, // onOpen
            msg -> {}, // onMessage - 由enhancedListener处理
            msg -> {}, // onClosing
            msg -> {}, // onFailure
            request,
            enhancedListener
        );
    }
    
    /**
     * 增强的全市场强平订单流（同时支持原有和新的回调）
     * 
     * @param onOpenCallback 原有连接打开回调
     * @param onMessageCallback 原有消息回调
     * @param onClosingCallback 原有连接关闭回调
     * @param onFailureCallback 原有失败回调
     * @param enhancedListener 增强的事件监听器
     * @return int - Connection ID
     */
    public int allForceOrderStreamEnhanced(
            WebSocketCallback onOpenCallback,
            WebSocketCallback onMessageCallback,
            WebSocketCallback onClosingCallback,
            WebSocketCallback onFailureCallback,
            EnhancedWebSocketListener enhancedListener
    ) {
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/!forceOrder@arr", baseUrl));
        return createEnhancedConnection(
            onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request, enhancedListener);
    }

    /**
     * Top bids and asks, Valid are 5, 10, or 20.
     * <br><br>
     * &lt;symbol&gt;@depth&lt;levels&gt;@&lt;speed&gt;ms
     * <br><br>
     * Update Speed: 250ms, 500ms or 100ms
     *
     * @param symbol trading symbol
     * @param levels order book depth level, can be 5, 10, or 20
     * @param speed  update speed  in ms, can be 250, 500 or 100
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Partial-Book-Depth-Streams">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Partial-Book-Depth-Streams</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Partial-Book-Depth-Streams">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Partial-Book-Depth-Streams</a>
     */
    @Override
    public int partialDepthStream(String symbol, int levels, int speed, WebSocketCallback onMessageCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        return partialDepthStream(symbol, levels, speed, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #partialDepthStream(String, int, int, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param symbol trading symbol
     * @param levels order book depth level, can be 5, 10, or 20
     * @param speed update speed in ms, can be 250, 500 or 100
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int partialDepthStream(String symbol, int levels, int speed, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");

        Request request = null;
        final int defaultSpeed = 250;
        if (speed == defaultSpeed) {
            request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@depth%s", baseUrl, symbol.toLowerCase(), levels));
        } else {
            request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@depth%s@%sms", baseUrl, symbol.toLowerCase(), levels, speed));
        }

        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * Bids and asks, pushed every 250 milliseconds, 500 milliseconds, 100 milliseconds (if existing)
     * <br><br>
     * &lt;symbol&gt;@depth@&lt;speed&gt;ms
     * <br><br>
     * Update Speed: 250ms, 500ms, 100ms
     *
     * @param symbol trading symbol
     * @param speed  update speed in ms, can be 250, 500 or 100
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Diff-Book-Depth-Streams">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Diff-Book-Depth-Streams</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Diff-Book-Depth-Streams">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Diff-Book-Depth-Streams</a>
     */
    @Override
    public int diffDepthStream(String symbol, int speed, WebSocketCallback onMessageCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");
        return diffDepthStream(symbol, speed, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #diffDepthStream(String, int, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param symbol trading symbol
     * @param speed update speed in ms, can be 250, 500 or 100
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int diffDepthStream(String symbol, int speed, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(symbol, String.class, "symbol");

        Request request = null;
        final int defaultSpeed = 250;
        if (speed == defaultSpeed) {
            request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@depth", baseUrl, symbol.toLowerCase(), speed));
        } else {
            request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@depth@%sms", baseUrl, symbol.toLowerCase(), speed));
        }
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);

    }

     /**
     * User Data Streams are accessed at /ws/&lt;listenKey&gt;
     *
     * @param listenKey listen key
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/user-data-streams/Connect">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/user-data-streams/Connect</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/user-data-streams/Connect">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/user-data-streams/Connect</a>
     */
    @Override
    public int listenUserStream(String listenKey, WebSocketCallback onMessageCallback) {
        return listenUserStream(listenKey, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #listenUserStream(String, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param listenKey listen key
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int listenUserStream(String listenKey, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s", baseUrl, listenKey));
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * Combined streams are accessed at /stream?streams=&lt;streamName1&gt;/&lt;streamName2&gt;/&lt;streamName3&gt;
     *
     * @param streams A list of stream names to be combined <br>
     * @param onMessageCallback onMessageCallback
     * @return int - Connection ID
     * @see <a href="https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Connect">
     * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Connect</a>
     * @see <a href="https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Connect">
     * https://developers.binance.com/docs/derivatives/coin-margined-futures/websocket-market-streams/Connect</a>
     */
    @Override
    public int combineStreams(ArrayList<String> streams, WebSocketCallback onMessageCallback) {
        return combineStreams(streams, noopCallback, onMessageCallback, noopCallback, noopCallback);
    }

    /**
     * Same as {@link #combineStreams(ArrayList, WebSocketCallback)} plus accepts callbacks for all major websocket connection events.
     *
     * @param streams stream name list
     * @param onOpenCallback onOpenCallback
     * @param onMessageCallback onMessageCallback
     * @param onClosingCallback onClosingCallback
     * @param onFailureCallback onFailureCallback
     * @return int - Connection ID
     */
    @Override
    public int combineStreams(ArrayList<String> streams, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        String url = UrlBuilder.buildStreamUrl(String.format("%s/stream", baseUrl), streams);
        Request request = RequestBuilder.buildWebsocketRequest(url);
        return createConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    /**
     * Closes a specific stream based on stream Id.
     *
     * @param connectionId Connection ID
     */
    @Override
    public void closeConnection(int connectionId) {
        if (connections.containsKey(connectionId)) {
            connections.get(connectionId).close();
            logger.info("Closing Connection ID {}", connectionId);
            connections.remove(connectionId);
        } else {
            logger.info("Connection ID {} does not exist!", connectionId);
        }
    }

    /**
     * Closes all streams
     */
    @Override
    public void closeAllConnections() {
        if (!connections.isEmpty()) {
            logger.info("Closing {} connections(s)", connections.size());
            Iterator<Map.Entry<Integer, WebSocketConnection>> iter = connections.entrySet().iterator();
            while (iter.hasNext()) {
                WebSocketConnection connection = iter.next().getValue();
                connection.shutdown(); // 使用shutdown而不是close，确保资源清理
                iter.remove();
            }
        }

        if (connections.isEmpty()) {
            // 注意：不再关闭全局HttpClient，因为每个连接现在都有独立的客户端
            // HttpClientSingleton.getHttpClient().dispatcher().executorService().shutdown();
            logger.info("All connections are closed!");
        }
    }

    public int createConnection(
            WebSocketCallback onOpenCallback,
            WebSocketCallback onMessageCallback,
            WebSocketCallback onClosingCallback,
            WebSocketCallback onFailureCallback,
            Request request
    ) {
        WebSocketConnection connection;
        
        if (autoReconnectEnabled) {
            // 使用增强连接（支持自动重连）
            logger.debug("Creating enhanced WebSocket connection with auto-reconnect for: {}", request.url());
            connection = new WebSocketConnection(
                onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, 
                request, defaultReconnectListener);
        } else {
            // 使用传统连接（向后兼容）
            logger.debug("Creating traditional WebSocket connection for: {}", request.url());
            connection = new WebSocketConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
        }
        
        connection.connect();
        int connectionId = connection.getConnectionId();
        connections.put(connectionId, connection);
        return connectionId;
    }
    
    /**
     * 创建增强的WebSocket连接（支持重连和状态管理）
     */
    public int createEnhancedConnection(
            WebSocketCallback onOpenCallback,
            WebSocketCallback onMessageCallback,
            WebSocketCallback onClosingCallback,
            WebSocketCallback onFailureCallback,
            Request request,
            EnhancedWebSocketListener enhancedListener
    ) {
        WebSocketConnection connection = new WebSocketConnection(
            onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request, enhancedListener);
        connection.connect();
        int connectionId = connection.getConnectionId();
        connections.put(connectionId, connection);
        return connectionId;
    }
}
