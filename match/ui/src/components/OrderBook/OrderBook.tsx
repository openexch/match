import { useMemo } from 'react';
import type { OrderBook as OrderBookType } from '../../types/market';
import { formatPrice, formatQuantity } from '../../utils/formatters';
import './OrderBook.css';

interface OrderBookProps {
  orderBook: OrderBookType;
}

export function OrderBook({ orderBook }: OrderBookProps) {
  const { bids, asks } = orderBook;

  const { askLevels, bidLevels, maxCumulative } = useMemo(() => {
    let askCum = 0;
    let bidCum = 0;

    // Asks: lowest price at top, cumulative grows downward
    const askLevels = asks.map(level => {
      askCum += level.quantity;
      return { ...level, cumulative: askCum };
    });

    // Bids: highest price at top, cumulative grows downward
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
      <div className="order-book-header">
        <div className="header-title">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M3 3v18h18"/>
            <path d="M18 9l-5 5-4-4-3 3"/>
          </svg>
          <h3>Order Book</h3>
        </div>
        <div className="header-stats">
          <span className="stat bid-stat">{bids.length} bids</span>
          <span className="stat ask-stat">{asks.length} asks</span>
        </div>
      </div>

      {/* Spread & Mid-price bar */}
      <div className="spread-bar">
        <div className="mid-price">
          <span className="label">Mid</span>
          <span className="value">${formatPrice(midPrice)}</span>
        </div>
        <div className="spread-info">
          <span className="label">Spread</span>
          <span className="value">${formatPrice(spread)} ({spreadPercent.toFixed(3)}%)</span>
        </div>
      </div>

      {/* Side-by-side order book */}
      <div className="book-sides">
        {/* Bids Side (Left) */}
        <div className="book-side bids-side">
          <div className="side-header">
            <span className="col total">Total</span>
            <span className="col amount">Amount</span>
            <span className="col price">Bid Price</span>
          </div>
          <div className="side-content">
            {bidLevels.map((level, i) => (
              <div key={`bid-${i}`} className="book-row bid">
                <div
                  className="depth-bar"
                  style={{ width: `${(level.cumulative / maxCumulative) * 100}%` }}
                />
                <span className="col total">{formatQuantity(level.cumulative)}</span>
                <span className="col amount">{formatQuantity(level.quantity)}</span>
                <span className="col price">${formatPrice(level.price)}</span>
              </div>
            ))}
            {bidLevels.length === 0 && (
              <div className="empty-side">No bids</div>
            )}
          </div>
        </div>

        {/* Asks Side (Right) */}
        <div className="book-side asks-side">
          <div className="side-header">
            <span className="col price">Ask Price</span>
            <span className="col amount">Amount</span>
            <span className="col total">Total</span>
          </div>
          <div className="side-content">
            {askLevels.map((level, i) => (
              <div key={`ask-${i}`} className="book-row ask">
                <div
                  className="depth-bar"
                  style={{ width: `${(level.cumulative / maxCumulative) * 100}%` }}
                />
                <span className="col price">${formatPrice(level.price)}</span>
                <span className="col amount">{formatQuantity(level.quantity)}</span>
                <span className="col total">{formatQuantity(level.cumulative)}</span>
              </div>
            ))}
            {askLevels.length === 0 && (
              <div className="empty-side">No asks</div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
