import { useState, useCallback } from 'react';
import { useWebSocket } from './hooks/useWebSocket';
import { useOrderBook } from './hooks/useOrderBook';
import { useTrades } from './hooks/useTrades';
import { useOrders } from './hooks/useOrders';
import { useMarketStats } from './hooks/useMarketStats';
import { useApi } from './hooks/useApi';
import { OrderBook } from './components/OrderBook/OrderBook';
import { TradeList } from './components/Trades/TradeList';
import { ConnectionStatus } from './components/ConnectionStatus/ConnectionStatus';
import { MarketSelector } from './components/MarketSelector/MarketSelector';
import { MarketStats } from './components/MarketStats/MarketStats';
import { OrderForm } from './components/OrderForm/OrderForm';
import { OpenOrders } from './components/OpenOrders/OpenOrders';
import { ClusterAdmin } from './components/ClusterAdmin';
import type { WebSocketMessage, Market, OrderRequest } from './types/market';
import { MARKETS } from './types/market';
import './App.css';

function App() {
  // Market state
  const [selectedMarket, setSelectedMarket] = useState<Market>(MARKETS[0]);
  const [showAdmin, setShowAdmin] = useState(false);

  // Data hooks
  const { orderBook, handleBookSnapshot, resetOrderBook } = useOrderBook();
  const { trades, handleTradesBatch, resetTrades } = useTrades();
  const { openOrders, handleOrderStatus, resetOrders } = useOrders();
  const { stats, handleTrades, handleBookUpdate, resetStats } = useMarketStats();
  const { submitOrder, cancelOrder, loading: apiLoading } = useApi();

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
        case 'SUBSCRIPTION_CONFIRMED':
          console.log('Subscribed to market', message.marketId);
          break;
        case 'PONG':
          // Heartbeat received
          break;
        case 'ERROR':
          console.error('Server error:', message.message);
          break;
      }
    },
    [handleBookSnapshot, handleTradesBatch, handleOrderStatus, handleBookUpdate, handleTrades]
  );

  const { status, reconnect } = useWebSocket({
    marketId: selectedMarket.id,
    onMessage: handleMessage,
  });

  const handleMarketChange = useCallback((market: Market) => {
    setSelectedMarket(market);
    resetOrderBook();
    resetTrades();
    resetOrders();
    resetStats();
  }, [resetOrderBook, resetTrades, resetOrders, resetStats]);

  const handleReconnect = useCallback(() => {
    resetOrderBook();
    resetTrades();
    resetOrders();
    resetStats();
    reconnect();
  }, [resetOrderBook, resetTrades, resetOrders, resetStats, reconnect]);

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
      {/* Top Navigation */}
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
          <button className="admin-btn" onClick={() => setShowAdmin(true)} title="Cluster Admin">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
              <path d="M19.14 12.94c.04-.31.06-.63.06-.94 0-.31-.02-.63-.06-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.04.31-.06.63-.06.94s.02.63.06.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/>
            </svg>
          </button>
          <ConnectionStatus status={status} onReconnect={handleReconnect} />
        </div>
      </header>

      {/* Market Stats Bar */}
      <div className="stats-bar">
        <MarketStats market={selectedMarket} stats={stats} orderBook={orderBook} />
      </div>

      {/* Main Trading Interface */}
      <main className="app-main">
        {/* Left Column: Order Form */}
        <aside className="left-panel">
          <OrderForm
            market={selectedMarket}
            bestBid={bestBid}
            bestAsk={bestAsk}
            onSubmitOrder={handleSubmitOrder}
            loading={apiLoading}
          />
        </aside>

        {/* Center Column: Order Book */}
        <section className="center-panel">
          <OrderBook orderBook={orderBook} />
        </section>

        {/* Right Column: Trades */}
        <aside className="right-panel">
          <TradeList trades={trades} />
        </aside>
      </main>

      {/* Bottom: Open Orders */}
      <section className="orders-panel">
        <OpenOrders
          orders={openOrders}
          onCancelOrder={handleCancelOrder}
          loading={apiLoading}
        />
      </section>

      {/* Footer */}
      <footer className="app-footer">
        <div className="footer-left">
          <span>Match Trading Engine</span>
          <span className="separator">|</span>
          <span className="version">v1.0.0</span>
        </div>
        <div className="footer-right">
          <span className="update-indicator" />
          <span className="update-rate">Live updates • 50ms</span>
        </div>
      </footer>

      {/* Cluster Admin Panel */}
      <ClusterAdmin isOpen={showAdmin} onClose={() => setShowAdmin(false)} />
    </div>
  );
}

export default App;
