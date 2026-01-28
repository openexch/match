import { useState, useCallback, useMemo, useRef } from 'react';
import { Routes, Route, Link } from 'react-router-dom';
import { useWebSocket } from './hooks/useWebSocket';
import { useOrderBook } from './hooks/useOrderBook';
import { useTrades } from './hooks/useTrades';
import { useMarketStats } from './hooks/useMarketStats';
import { useClusterState } from './hooks/useClusterState';
import { useApi } from './hooks/useApi';
import { OrderBook } from './components/OrderBook/OrderBook';
import { TradeList } from './components/Trades/TradeList';
import { Chart } from './components/Chart/Chart';
import { ConnectionStatus } from './components/ConnectionStatus/ConnectionStatus';
import { MarketSelector } from './components/MarketSelector/MarketSelector';
import { MarketStats } from './components/MarketStats/MarketStats';
import { OrderForm } from './components/OrderForm/OrderForm';
import { AdminPage } from './pages/AdminPage';
import type { WebSocketMessage, Market, OrderRequest, ClusterStatusMessage, ClusterEventMessage, ExtendedConnectionStatus, BookDeltaMessage, TickerStatsMessage, CandleData, CandleHistoryMessage, CandleUpdateMessage } from './types/market';
import { MARKETS } from './types/market';
import './App.css';

// Icons
const Icons = {
  settings: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="3"/>
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>
    </svg>
  ),
  activity: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>
    </svg>
  ),
  initexLogo: (
    <svg viewBox="0 0 40 40" fill="none">
      <circle cx="8" cy="12" r="4" fill="currentColor"/>
      <circle cx="8" cy="32" r="4" fill="currentColor"/>
      <circle cx="22" cy="22" r="4" fill="currentColor"/>
      <circle cx="32" cy="8" r="4" fill="currentColor"/>
      <line x1="8" y1="16" x2="8" y2="28" stroke="currentColor" strokeWidth="2"/>
      <line x1="11" y1="13" x2="19" y2="20" stroke="currentColor" strokeWidth="2"/>
      <line x1="11" y1="31" x2="19" y2="24" stroke="currentColor" strokeWidth="2"/>
      <line x1="25" y1="20" x2="29" y2="11" stroke="currentColor" strokeWidth="2"/>
    </svg>
  ),
};

function MarketPage() {
  const [selectedMarket, setSelectedMarket] = useState<Market>(MARKETS[0]);
  const selectedMarketIdRef = useRef(selectedMarket.id);

  // Price click-to-fill state
  const [clickedPrice, setClickedPrice] = useState<number | null>(null);

  // Server-aggregated candle state
  const [candles, setCandles] = useState<CandleData[]>([]);
  const [currentCandle, setCurrentCandle] = useState<CandleData | null>(null);
  const [chartInterval, setChartInterval] = useState<string>('1m');
  const chartIntervalRef = useRef<string>('1m');

  const { orderBook, levelChanges, handleBookSnapshot, handleBookDelta, resetOrderBook } = useOrderBook();
  const { trades, handleTradesBatch, resetTrades } = useTrades();
  const { stats, setStats, handleTrades, handleBookUpdate, resetStats } = useMarketStats();
  const { clusterState, handleClusterStatus, handleClusterEvent } = useClusterState();
  const { submitOrder, loading: apiLoading } = useApi();

  const resetAllState = useCallback(() => {
    resetOrderBook();
    resetTrades();
    resetStats();
    setCandles([]);
    setCurrentCandle(null);
  }, [resetOrderBook, resetTrades, resetStats]);

  const handleReconnecting = useCallback(() => {
    resetAllState();
  }, [resetAllState]);

  const handleReconnected = useCallback(() => {}, []);

  const handleMessage = useCallback(
    (message: WebSocketMessage) => {
      switch (message.type) {
        case 'BOOK_SNAPSHOT':
          if (Number(message.marketId) === selectedMarketIdRef.current) {
            handleBookSnapshot(message);
            handleBookUpdate(message.bids, message.asks);
          }
          break;
        case 'BOOK_DELTA':
          if (message.marketId === selectedMarketIdRef.current) {
            handleBookDelta(message as BookDeltaMessage);
          }
          break;
        case 'TRADES_BATCH':
          if (message.marketId === selectedMarketIdRef.current) {
            handleTradesBatch(message);
            handleTrades(message.trades);
          }
          break;
        case 'ORDER_STATUS':
        case 'ORDER_STATUS_BATCH':
          break;
        case 'SUBSCRIPTION_CONFIRMED':
          break;
        case 'PONG':
          break;
        case 'ERROR':
          console.error('Server error:', message.message);
          break;
        case 'TICKER_STATS':
          if ((message as TickerStatsMessage).marketId === selectedMarketIdRef.current) {
            const tickerMsg = message as TickerStatsMessage;
            setStats({
              lastPrice: tickerMsg.lastPrice,
              priceChange: tickerMsg.priceChange,
              priceChangePercent: tickerMsg.priceChangePercent,
              high24h: tickerMsg.high24h,
              low24h: tickerMsg.low24h,
              volume24h: tickerMsg.volume24h,
            });
          }
          break;
        case 'CANDLE_HISTORY': {
          const candleHist = message as CandleHistoryMessage;
          if (candleHist.marketId === selectedMarketIdRef.current) {
            // Only use 1m history from WS; for other intervals we fetch via REST
            if (chartIntervalRef.current === '1m' || candleHist.interval === chartIntervalRef.current) {
              setCandles(candleHist.candles);
              setCurrentCandle(null);
            }
          }
          break;
        }
        case 'CANDLE_UPDATE': {
          const candleUpd = message as CandleUpdateMessage;
          if (candleUpd.marketId === selectedMarketIdRef.current && candleUpd.interval === '1m') {
            if (chartIntervalRef.current === '1m') {
              // Update current candle for real-time chart updates
              setCurrentCandle(candleUpd.candle);
              // If this is a new candle (different time from last in history), append to history
              setCandles(prev => {
                if (prev.length === 0) return [candleUpd.candle];
                const last = prev[prev.length - 1];
                if (candleUpd.candle.time > last.time) {
                  // New candle bucket — append and shift
                  return [...prev, candleUpd.candle];
                }
                // Same bucket — history will be updated via currentCandle overlay
                return prev;
              });
            }
          }
          break;
        }
        case 'CLUSTER_STATUS':
          handleClusterStatus(message as ClusterStatusMessage);
          break;
        case 'CLUSTER_EVENT':
          handleClusterEvent(message as ClusterEventMessage);
          break;
      }
    },
    [handleBookSnapshot, handleBookDelta, handleTradesBatch, setStats,
     handleBookUpdate, handleTrades, handleClusterStatus, handleClusterEvent]
  );

  const { status, forceReconnect } = useWebSocket({
    marketId: selectedMarket.id,
    onMessage: handleMessage,
    onReconnecting: handleReconnecting,
    onReconnected: handleReconnected,
  });

  // Fetch candles from REST API for non-1m intervals
  const fetchCandles = useCallback(async (marketId: number, interval: string, limit: number = 200) => {
    try {
      const apiBase = import.meta.env.VITE_MARKET_WS_URL
        ? import.meta.env.VITE_MARKET_WS_URL.replace(/^wss?:/, window.location.protocol === 'https:' ? 'https:' : 'http:')
        : `http://${window.location.hostname}:8081`;
      const res = await fetch(`${apiBase}/api/candles?marketId=${marketId}&interval=${interval}&limit=${limit}`);
      if (res.ok) {
        const data = await res.json();
        if (data.candles && data.marketId === selectedMarketIdRef.current) {
          setCandles(data.candles);
          setCurrentCandle(null);
        }
      }
    } catch (e) {
      console.error('Failed to fetch candles:', e);
    }
  }, []);

  const handleIntervalChange = useCallback((interval: string) => {
    chartIntervalRef.current = interval;
    setChartInterval(interval);
    setCandles([]);
    setCurrentCandle(null);

    if (interval === '1m') {
      // Re-fetch 1m from REST (WS will keep updating via CANDLE_UPDATE)
      fetchCandles(selectedMarketIdRef.current, '1m');
    } else {
      // Fetch from REST for non-1m intervals
      fetchCandles(selectedMarketIdRef.current, interval);
    }
  }, [fetchCandles]);

  const effectiveStatus: ExtendedConnectionStatus = useMemo(() => {
    if (status !== 'connected') return status;
    if (clusterState.isElecting) return 'cluster-electing';
    if (clusterState.isRollingUpdate) return 'cluster-updating';
    return status;
  }, [status, clusterState.isElecting, clusterState.isRollingUpdate]);

  const handleMarketChange = useCallback((market: Market) => {
    selectedMarketIdRef.current = market.id;
    setSelectedMarket(market);
    chartIntervalRef.current = '1m';
    setChartInterval('1m');
    resetAllState();
  }, [resetAllState]);

  const handleReconnect = useCallback(() => {
    forceReconnect();
  }, [forceReconnect]);

  const handleSubmitOrder = useCallback(async (order: OrderRequest) => {
    return await submitOrder(order);
  }, [submitOrder]);

  // Order book price click → fills order form
  const handlePriceClick = useCallback((price: number) => {
    setClickedPrice(price);
  }, []);

  const bestBid = orderBook.bids.length > 0 ? orderBook.bids[0] : null;
  const bestAsk = orderBook.asks.length > 0 ? orderBook.asks[0] : null;

  return (
    <div className="app">
      <header className="app-header">
        <div className="header-left">
          <div className="logo">
            <span className="logo-icon">{Icons.initexLogo}</span>
            <span className="logo-text"><span className="init">init</span><span className="ex">EX</span></span>
          </div>
          <MarketSelector
            markets={MARKETS}
            selectedMarket={selectedMarket}
            onSelectMarket={handleMarketChange}
          />
        </div>
        <div className="header-right">
          <Link to="/admin" className="admin-btn" title="Cluster Admin">
            {Icons.settings}
          </Link>
          <ConnectionStatus status={effectiveStatus} clusterState={clusterState} onReconnect={handleReconnect} />
        </div>
      </header>

      <div className="stats-bar">
        <MarketStats market={selectedMarket} stats={stats} orderBook={orderBook} />
      </div>

      <main className="app-main">
        {/* Left sidebar — Order Book (vertical) */}
        <aside className="left-panel">
          <OrderBook
            orderBook={orderBook}
            levelChanges={levelChanges}
            onPriceClick={handlePriceClick}
          />
        </aside>

        {/* Center — Chart + Order Form */}
        <section className="center-panel">
          <div className="chart-area">
            <Chart
              candles={candles}
              currentCandle={currentCandle}
              symbol={selectedMarket.symbol}
              onIntervalChange={handleIntervalChange}
              activeInterval={chartInterval}
            />
          </div>
          <div className="order-area">
            <OrderForm
              market={selectedMarket}
              bestBid={bestBid}
              bestAsk={bestAsk}
              onSubmitOrder={handleSubmitOrder}
              loading={apiLoading}
              externalPrice={clickedPrice}
            />
          </div>
        </section>

        {/* Right sidebar — Recent Trades */}
        <aside className="right-panel">
          <TradeList trades={trades} />
        </aside>
      </main>

      <footer className="app-footer">
        <div className="footer-left">
          <span className="footer-icon">{Icons.activity}</span>
          <span>initEX Trading Engine</span>
          <span className="separator">|</span>
          <span className="version">v1.0.0</span>
        </div>
        <div className="footer-right">
          <span className="update-indicator" />
          <span className="update-rate">Live updates</span>
        </div>
      </footer>
    </div>
  );
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<MarketPage />} />
      <Route path="/admin" element={<AdminPage />} />
    </Routes>
  );
}

export default App;
