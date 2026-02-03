import type { MarketStats as Stats, Market, OrderBook } from '../../types/market';
import { formatPrice, formatQuantity } from '../../utils/formatters';
import './MarketStats.css';

interface MarketStatsProps {
  market: Market;
  stats: Stats;
  orderBook: OrderBook;
}

export function MarketStats({ market, stats, orderBook }: MarketStatsProps) {
  const spread = orderBook.bids.length > 0 && orderBook.asks.length > 0
    ? orderBook.asks[0].price - orderBook.bids[0].price
    : 0;
  const spreadPercent = orderBook.asks.length > 0 && spread > 0
    ? (spread / orderBook.asks[0].price) * 100
    : 0;

  const priceChange = stats.priceChange ?? 0;
  const priceChangePercent = stats.priceChangePercent ?? 0;
  const isPositive = priceChange >= 0;

  return (
    <div className="market-stats">
      <div className="stat-item primary">
        <span className="stat-label">{market.symbol}</span>
        <span className={`stat-value large ${isPositive ? 'positive' : 'negative'}`}>
          ${formatPrice(stats.lastPrice || (orderBook.bids[0]?.price || 0))}
        </span>
      </div>

      <div className="stat-item">
        <span className="stat-label">24h Change</span>
        <span className={`stat-value ${isPositive ? 'positive' : 'negative'}`}>
          {isPositive ? '▲ +' : '▼ '}{formatPrice(priceChange)} ({isPositive ? '+' : ''}{priceChangePercent.toFixed(2)}%)
        </span>
      </div>

      <div className="stat-item">
        <span className="stat-label">24h High</span>
        <span className="stat-value">${formatPrice(stats.high24h)}</span>
      </div>

      <div className="stat-item">
        <span className="stat-label">24h Low</span>
        <span className="stat-value">${formatPrice(stats.low24h)}</span>
      </div>

      <div className="stat-item">
        <span className="stat-label">24h Volume</span>
        <span className="stat-value">${formatQuantity(stats.volume24h)}</span>
      </div>

      <div className="stat-item">
        <span className="stat-label">Spread</span>
        <span className="stat-value">
          ${formatPrice(spread)} ({spreadPercent.toFixed(3)}%)
        </span>
      </div>
    </div>
  );
}
