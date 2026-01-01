import { useCallback } from 'react';
import { useWebSocket } from './hooks/useWebSocket';
import { useOrderBook } from './hooks/useOrderBook';
import { useTrades } from './hooks/useTrades';
import { OrderBook } from './components/OrderBook/OrderBook';
import { TradeList } from './components/Trades/TradeList';
import { ConnectionStatus } from './components/ConnectionStatus/ConnectionStatus';
import type { WebSocketMessage } from './types/market';
import './App.css';

const MARKET_ID = 1; // BTC-USD

function App() {
  const { orderBook, handleBookSnapshot, resetOrderBook } = useOrderBook();
  const { trades, handleTradesBatch, resetTrades } = useTrades();

  const handleMessage = useCallback(
    (message: WebSocketMessage) => {
      switch (message.type) {
        case 'BOOK_SNAPSHOT':
          handleBookSnapshot(message);
          break;
        case 'TRADES_BATCH':
          handleTradesBatch(message);
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
    [handleBookSnapshot, handleTradesBatch]
  );

  const { status, reconnect } = useWebSocket({
    marketId: MARKET_ID,
    onMessage: handleMessage,
  });

  const handleReconnect = useCallback(() => {
    resetOrderBook();
    resetTrades();
    reconnect();
  }, [resetOrderBook, resetTrades, reconnect]);

  return (
    <div className="app">
      <header className="app-header">
        <h1>BTC-USD</h1>
        <ConnectionStatus status={status} onReconnect={handleReconnect} />
      </header>

      <main className="app-main">
        <OrderBook orderBook={orderBook} />
        <TradeList trades={trades} />
      </main>

      <footer className="app-footer">
        <span>Match Trading Engine</span>
        <span className="update-rate">Updates every 50ms</span>
      </footer>
    </div>
  );
}

export default App;
