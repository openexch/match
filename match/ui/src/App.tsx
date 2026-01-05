import { useState, useCallback, useMemo } from 'react';
import { Routes, Route, Link } from 'react-router-dom';
import { useWebSocket } from './hooks/useWebSocket';
import { useOrderBook } from './hooks/useOrderBook';
import { useTrades } from './hooks/useTrades';
import { useOrders } from './hooks/useOrders';
import { useMarketStats } from './hooks/useMarketStats';
import { useClusterState } from './hooks/useClusterState';
import { useApi } from './hooks/useApi';
import { OrderBook } from './components/OrderBook/OrderBook';
import { TradeList } from './components/Trades/TradeList';
import { ConnectionStatus } from './components/ConnectionStatus/ConnectionStatus';
import { MarketSelector } from './components/MarketSelector/MarketSelector';
import { MarketStats } from './components/MarketStats/MarketStats';
import { OrderForm } from './components/OrderForm/OrderForm';
import { OpenOrders } from './components/OpenOrders/OpenOrders';
import { AdminPage } from './pages/AdminPage';
import type { WebSocketMessage, Market, OrderRequest, ClusterStatusMessage, ClusterEventMessage, ExtendedConnectionStatus } from './types/market';
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
};

function MarketPage() {
  // Market state
  const [selectedMarket, setSelectedMarket] = useState<Market>(MARKETS[0]);

  // Data hooks
  const { orderBook, handleBookSnapshot, resetOrderBook } = useOrderBook();
  const { trades, handleTradesBatch, resetTrades } = useTrades();
  const { openOrders, handleOrderStatus, handleOrderStatusBatch, resetOrders } = useOrders();
  const { stats, handleTrades, handleBookUpdate, resetStats } = useMarketStats();
  const { clusterState, handleClusterStatus, handleClusterEvent } = useClusterState();
  const { submitOrder, cancelOrder, loading: apiLoading } = useApi();

  const resetAllState = useCallback(() => {
    resetOrderBook();
    resetTrades();
    resetOrders();
    resetStats();
  }, [resetOrderBook, resetTrades, resetOrders, resetStats]);

  const handleReconnecting = useCallback(() => {
    resetAllState();
  }, [resetAllState]);

  const handleReconnected = useCallback(() => {}, []);

  const handleMessage = useCallback(
    (message: WebSocketMessage) => {
      switch (message.type) {
        case 'BOOK_SNAPSHOT':
          handleBookSnapshot(message);
          handleBookUpdate(message.bids, message.asks);
          break;
        case 'TRADES_BATCH':
          handleTradesBatch(message);
          handleTrades(message.trades);
          break;
        case 'ORDER_STATUS':
          handleOrderStatus(message);
          break;
        case 'ORDER_STATUS_BATCH':
          handleOrderStatusBatch(message);
          break;
        case 'SUBSCRIPTION_CONFIRMED':
          break;
        case 'PONG':
          break;
        case 'ERROR':
          console.error('Server error:', message.message);
          break;
        case 'CLUSTER_STATUS':
          handleClusterStatus(message as ClusterStatusMessage);
          break;
        case 'CLUSTER_EVENT':
          handleClusterEvent(message as ClusterEventMessage);
          if ((message as ClusterEventMessage).event === 'LEADER_CHANGE') {
            resetOrders();
          }
          break;
      }
    },
    [handleBookSnapshot, handleTradesBatch, handleOrderStatus, handleOrderStatusBatch,
     handleBookUpdate, handleTrades, handleClusterStatus, handleClusterEvent, resetOrders]
  );

  const { status, forceReconnect } = useWebSocket({
    marketId: selectedMarket.id,
    onMessage: handleMessage,
    onReconnecting: handleReconnecting,
    onReconnected: handleReconnected,
  });

  const effectiveStatus: ExtendedConnectionStatus = useMemo(() => {
    if (status !== 'connected') return status;
    if (clusterState.isElecting) return 'cluster-electing';
    if (clusterState.isRollingUpdate) return 'cluster-updating';
    return status;
  }, [status, clusterState.isElecting, clusterState.isRollingUpdate]);

  const handleMarketChange = useCallback((market: Market) => {
    setSelectedMarket(market);
    resetAllState();
  }, [resetAllState]);

  const handleReconnect = useCallback(() => {
    forceReconnect();
  }, [forceReconnect]);

  const handleSubmitOrder = useCallback(async (order: OrderRequest) => {
    return await submitOrder(order);
  }, [submitOrder]);

  const handleCancelOrder = useCallback(async (orderId: number) => {
    await cancelOrder(orderId, '1', selectedMarket.symbol);
  }, [cancelOrder, selectedMarket.symbol]);

  const bestBid = orderBook.bids.length > 0 ? orderBook.bids[0] : null;
  const bestAsk = orderBook.asks.length > 0 ? orderBook.asks[0] : null;

  return (
    <div className="app">
      <header className="app-header">
        <div className="header-left">
          <div className="logo">
            <span className="logo-icon">M</span>
            <span className="logo-text">Match</span>
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
        <aside className="left-panel">
          <OrderForm
            market={selectedMarket}
            bestBid={bestBid}
            bestAsk={bestAsk}
            onSubmitOrder={handleSubmitOrder}
            loading={apiLoading}
          />
        </aside>

        <section className="center-panel">
          <OrderBook orderBook={orderBook} />
        </section>

        <aside className="right-panel">
          <TradeList trades={trades} />
        </aside>
      </main>

      <section className="orders-panel">
        <OpenOrders
          orders={openOrders}
          onCancelOrder={handleCancelOrder}
          loading={apiLoading}
        />
      </section>

      <footer className="app-footer">
        <div className="footer-left">
          <span className="footer-icon">{Icons.activity}</span>
          <span>Match Trading Engine</span>
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
