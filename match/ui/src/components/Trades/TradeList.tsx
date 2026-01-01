import type { Trade } from '../../types/market';
import { formatPrice, formatQuantity, formatTime } from '../../utils/formatters';
import './TradeList.css';

interface TradeListProps {
  trades: Trade[];
}

export function TradeList({ trades }: TradeListProps) {
  return (
    <div className="trade-list">
      <h2>Recent Trades</h2>
      <div className="trade-list-header">
        <span>Price (USD)</span>
        <span>Quantity (BTC)</span>
        <span>Time</span>
      </div>

      <div className="trade-list-body">
        {trades.length === 0 ? (
          <div className="no-trades">Waiting for trades...</div>
        ) : (
          trades.map((trade) => (
            <div
              key={trade.tradeId}
              className={`trade-row ${trade.takerSide === 'BID' ? 'buy' : 'sell'}`}
            >
              <span className="trade-price">
                {formatPrice(trade.price)}
              </span>
              <span className="trade-quantity">
                {formatQuantity(trade.quantity)}
              </span>
              <span className="trade-time">
                {formatTime(trade.timestamp)}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
