import { useMemo, useState } from 'react';
import type { OrderBook as OrderBookType } from '../../types/market';
import { type LevelChange, priceKey } from '../../hooks/useOrderBook';
import { formatPrice, formatQuantity } from '../../utils/formatters';
import './OrderBook.css';

type ViewMode = 'vertical' | 'horizontal' | 'bids-only' | 'asks-only';

interface OrderBookProps {
  orderBook: OrderBookType;
  levelChanges: Map<string, LevelChange>;
  onPriceClick?: (price: number) => void;
}

export function OrderBook({ orderBook, levelChanges, onPriceClick }: OrderBookProps) {
  const { bids, asks } = orderBook;
  const [viewMode, setViewMode] = useState<ViewMode>('vertical');

  const { askLevels, bidLevels, maxCumulative } = useMemo(() => {
    let askCum = 0;
    let bidCum = 0;

    // Asks: lowest price first, cumulative from lowest
    const askLevels = asks.map(level => {
      askCum += level.quantity;
      return { ...level, cumulative: askCum };
    });

    // Bids: highest price first, cumulative from highest
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

  const getAnimationClass = (price: number, side: 'bid' | 'ask'): string => {
    const change = levelChanges.get(priceKey(price));
    if (!change) return '';
    if (change.type === 'new') {
      return side === 'bid' ? 'new-bid' : 'new-ask';
    }
    return 'flash';
  };

  const handlePriceClick = (price: number) => {
    if (onPriceClick) {
      onPriceClick(price);
    }
  };

  const showAsks = viewMode === 'vertical' || viewMode === 'horizontal' || viewMode === 'asks-only';
  const showBids = viewMode === 'vertical' || viewMode === 'horizontal' || viewMode === 'bids-only';
  const isVertical = viewMode !== 'horizontal';

  // For vertical mode, reverse asks so lowest price is at bottom (near spread)
  const displayAsks = isVertical ? [...askLevels].reverse() : askLevels;

  return (
    <div className={`order-book ${isVertical ? 'vertical-mode' : 'horizontal-mode'}`}>
      <div className="order-book-header">
        <div className="header-title">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M3 3v18h18"/>
            <path d="M18 9l-5 5-4-4-3 3"/>
          </svg>
          <h3>Order Book</h3>
        </div>
        <div className="view-toggles">
          <button
            className={`view-btn ${viewMode === 'vertical' ? 'active' : ''}`}
            onClick={() => setViewMode('vertical')}
            title="Vertical view"
          >
            <svg viewBox="0 0 16 16" fill="currentColor">
              <rect x="2" y="1" width="12" height="3" rx="0.5" opacity="0.4"/>
              <rect x="2" y="6" width="12" height="3" rx="0.5" opacity="0.7"/>
              <rect x="2" y="11" width="12" height="3" rx="0.5"/>
            </svg>
          </button>
          <button
            className={`view-btn ${viewMode === 'horizontal' ? 'active' : ''}`}
            onClick={() => setViewMode('horizontal')}
            title="Horizontal view"
          >
            <svg viewBox="0 0 16 16" fill="currentColor">
              <rect x="1" y="2" width="6" height="12" rx="0.5"/>
              <rect x="9" y="2" width="6" height="12" rx="0.5" opacity="0.4"/>
            </svg>
          </button>
          <button
            className={`view-btn bids-btn ${viewMode === 'bids-only' ? 'active' : ''}`}
            onClick={() => setViewMode('bids-only')}
            title="Bids only"
          >
            <svg viewBox="0 0 16 16" fill="currentColor">
              <rect x="2" y="2" width="12" height="12" rx="1"/>
            </svg>
          </button>
          <button
            className={`view-btn asks-btn ${viewMode === 'asks-only' ? 'active' : ''}`}
            onClick={() => setViewMode('asks-only')}
            title="Asks only"
          >
            <svg viewBox="0 0 16 16" fill="currentColor">
              <rect x="2" y="2" width="12" height="12" rx="1"/>
            </svg>
          </button>
        </div>
      </div>

      {isVertical ? (
        /* ═══ VERTICAL LAYOUT ═══ */
        <div className="book-vertical">
          {/* Column headers */}
          <div className="vertical-header">
            <span className="col price">Price</span>
            <span className="col amount">Amount</span>
            <span className="col total">Total</span>
          </div>

          {/* Asks section (reversed — lowest at bottom) */}
          {showAsks && (
            <div className="vertical-section asks-section">
              {displayAsks.map((level) => (
                <div
                  key={level.price}
                  className={`book-row ask ${getAnimationClass(level.price, 'ask')}`}
                  onClick={() => handlePriceClick(level.price)}
                  title="Click to set price"
                >
                  <div
                    className="depth-bar"
                    style={{ width: `${(level.cumulative / maxCumulative) * 100}%` }}
                  />
                  <span className="col price">${formatPrice(level.price)}</span>
                  <span className="col amount">{formatQuantity(level.quantity)}</span>
                  <span className="col total">{formatQuantity(level.cumulative)}</span>
                </div>
              ))}
              {displayAsks.length === 0 && (
                <div className="empty-side">No asks</div>
              )}
            </div>
          )}

          {/* Spread indicator */}
          <div className="spread-indicator">
            <span className="spread-price">${formatPrice(midPrice)}</span>
            <span className="spread-label">
              Spread: ${formatPrice(spread)} ({spreadPercent.toFixed(3)}%)
            </span>
          </div>

          {/* Bids section (highest at top) */}
          {showBids && (
            <div className="vertical-section bids-section">
              {bidLevels.map((level) => (
                <div
                  key={level.price}
                  className={`book-row bid ${getAnimationClass(level.price, 'bid')}`}
                  onClick={() => handlePriceClick(level.price)}
                  title="Click to set price"
                >
                  <div
                    className="depth-bar"
                    style={{ width: `${(level.cumulative / maxCumulative) * 100}%` }}
                  />
                  <span className="col price">${formatPrice(level.price)}</span>
                  <span className="col amount">{formatQuantity(level.quantity)}</span>
                  <span className="col total">{formatQuantity(level.cumulative)}</span>
                </div>
              ))}
              {bidLevels.length === 0 && (
                <div className="empty-side">No bids</div>
              )}
            </div>
          )}
        </div>
      ) : (
        /* ═══ HORIZONTAL LAYOUT (original side-by-side) ═══ */
        <>
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

          <div className="book-sides">
            <div className="book-side bids-side">
              <div className="side-header">
                <span className="col total">Total</span>
                <span className="col amount">Amount</span>
                <span className="col price">Bid Price</span>
              </div>
              <div className="side-content">
                {bidLevels.map((level) => (
                  <div
                    key={level.price}
                    className={`book-row bid ${getAnimationClass(level.price, 'bid')}`}
                    onClick={() => handlePriceClick(level.price)}
                    title="Click to set price"
                  >
                    <div
                      className="depth-bar"
                      style={{ width: `${(level.cumulative / maxCumulative) * 100}%` }}
                    />
                    <span className="col total">{formatQuantity(level.cumulative)}</span>
                    <span className="col amount">{formatQuantity(level.quantity)}</span>
                    <span className="col price">${formatPrice(level.price)}</span>
                  </div>
                ))}
                {bidLevels.length === 0 && <div className="empty-side">No bids</div>}
              </div>
            </div>

            <div className="book-side asks-side">
              <div className="side-header">
                <span className="col price">Ask Price</span>
                <span className="col amount">Amount</span>
                <span className="col total">Total</span>
              </div>
              <div className="side-content">
                {askLevels.map((level) => (
                  <div
                    key={level.price}
                    className={`book-row ask ${getAnimationClass(level.price, 'ask')}`}
                    onClick={() => handlePriceClick(level.price)}
                    title="Click to set price"
                  >
                    <div
                      className="depth-bar"
                      style={{ width: `${(level.cumulative / maxCumulative) * 100}%` }}
                    />
                    <span className="col price">${formatPrice(level.price)}</span>
                    <span className="col amount">{formatQuantity(level.quantity)}</span>
                    <span className="col total">{formatQuantity(level.cumulative)}</span>
                  </div>
                ))}
                {askLevels.length === 0 && <div className="empty-side">No asks</div>}
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
