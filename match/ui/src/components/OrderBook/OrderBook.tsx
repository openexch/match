import { useMemo } from 'react';
import type { OrderBook as OrderBookType } from '../../types/market';
import { formatPrice, formatQuantity } from '../../utils/formatters';
import './OrderBook.css';

interface OrderBookProps {
  orderBook: OrderBookType;
}

export function OrderBook({ orderBook }: OrderBookProps) {
  const { bids, asks } = orderBook;

  // Calculate cumulative quantities and max for depth visualization
  const { askLevels, bidLevels, maxCumulative } = useMemo(() => {
    let askCum = 0;
    let bidCum = 0;

    const askLevels = asks.slice().reverse().map(level => {
      askCum += level.quantity;
      return { ...level, cumulative: askCum };
    });

    const bidLevels = bids.map(level => {
      bidCum += level.quantity;
      return { ...level, cumulative: bidCum };
    });

    const maxCum = Math.max(
      askLevels.length > 0 ? askLevels[askLevels.length - 1].cumulative : 0,
      bidLevels.length > 0 ? bidLevels[bidLevels.length - 1].cumulative : 0,
      1
    );

    return { askLevels, bidLevels, maxCumulative: maxCum };
  }, [asks, bids]);

  const spread = bids.length > 0 && asks.length > 0
    ? asks[0].price - bids[0].price
    : 0;
  const spreadPercent = asks.length > 0 && spread > 0
    ? (spread / asks[0].price) * 100
    : 0;

  const midPrice = bids.length > 0 && asks.length > 0
    ? (asks[0].price + bids[0].price) / 2
    : 0;

  return (
    <div className="order-book">
      <div className="order-book-header-bar">
        <h3>Order Book</h3>
        <div className="book-summary">
          <span className="bid-count">{bids.length} bids</span>
          <span className="separator">•</span>
          <span className="ask-count">{asks.length} asks</span>
        </div>
      </div>

      <div className="order-book-columns">
        <span>Price (USD)</span>
        <span>Amount</span>
        <span>Total</span>
      </div>

      <div className="order-book-content">
        {/* Asks - reversed to show lowest ask at bottom */}
        <div className="order-book-asks">
          {askLevels.map((level, i) => (
            <div key={`ask-${i}`} className="order-book-row ask">
              <div
                className="depth-bar ask-bar"
                style={{ width: `${(level.cumulative / maxCumulative) * 100}%` }}
              />
              <span className="price">${formatPrice(level.price)}</span>
              <span className="quantity">{formatQuantity(level.quantity)}</span>
              <span className="cumulative">{formatQuantity(level.cumulative)}</span>
            </div>
          ))}
        </div>

        {/* Spread indicator */}
        <div className="order-book-spread">
          <div className="spread-info">
            <span className="mid-price">${formatPrice(midPrice)}</span>
            <span className="spread-value">
              Spread: ${formatPrice(spread)} ({spreadPercent.toFixed(3)}%)
            </span>
          </div>
        </div>

        {/* Bids */}
        <div className="order-book-bids">
          {bidLevels.map((level, i) => (
            <div key={`bid-${i}`} className="order-book-row bid">
              <div
                className="depth-bar bid-bar"
                style={{ width: `${(level.cumulative / maxCumulative) * 100}%` }}
              />
              <span className="price">${formatPrice(level.price)}</span>
              <span className="quantity">{formatQuantity(level.quantity)}</span>
              <span className="cumulative">{formatQuantity(level.cumulative)}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
