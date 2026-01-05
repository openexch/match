import type { AggregatedTrade } from '../../types/market';
import { formatPrice, formatQuantity, formatTime } from '../../utils/formatters';
import './TradeList.css';

interface TradeListProps {
  trades: AggregatedTrade[];
}

export function TradeList({ trades }: TradeListProps) {
  return (
    <div className="trade-list">
      <div className="trade-list-header-bar">
        <h3>Recent Trades</h3>
        <span className="trade-count">{trades.length}</span>
      </div>

      <div className="trade-list-columns">
        <span>Price (USD)</span>
        <span>Amount</span>
        <span>Time</span>
      </div>

      <div className="trade-list-body">
        {trades.length === 0 ? (
          <div className="no-trades">
            <svg className="no-trades-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M3 3v18h18" strokeLinecap="round" strokeLinejoin="round"/>
              <path d="M7 14l4-4 4 4 5-5" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
            <span>Waiting for trades...</span>
          </div>
        ) : (
          trades.map((trade, index) => {
            const isBuyDominant = trade.buyCount > trade.sellCount;
            return (
              <div
                key={`${trade.timestamp}-${index}`}
                className={`trade-row ${isBuyDominant ? 'buy' : 'sell'}`}
              >
                <span className="trade-price">
                  ${formatPrice(trade.price)}
                </span>
                <span className="trade-quantity">
                  {formatQuantity(trade.quantity)}
                  {trade.tradeCount > 1 && (
                    <span className="trade-count-badge">×{trade.tradeCount}</span>
                  )}
                </span>
                <span className="trade-time">
                  {formatTime(trade.timestamp)}
                </span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
