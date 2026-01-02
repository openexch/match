import { useState, useCallback, useMemo } from 'react';
import type { Market, OrderSide, OrderType, OrderRequest, BookLevel } from '../../types/market';
import { formatPrice } from '../../utils/formatters';
import './OrderForm.css';

interface OrderFormProps {
  market: Market;
  bestBid: BookLevel | null;
  bestAsk: BookLevel | null;
  onSubmitOrder: (order: OrderRequest) => Promise<{ success: boolean; message: string }>;
  loading: boolean;
}

const USER_ID = '1'; // In production, this would come from auth

export function OrderForm({ market, bestBid, bestAsk, onSubmitOrder, loading }: OrderFormProps) {
  const [side, setSide] = useState<OrderSide>('BID');
  const [orderType, setOrderType] = useState<OrderType>('LIMIT');
  const [price, setPrice] = useState('');
  const [quantity, setQuantity] = useState('');
  const [notification, setNotification] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  const isBuy = side === 'BID';

  // Calculate total
  const total = useMemo(() => {
    const p = parseFloat(price) || 0;
    const q = parseFloat(quantity) || 0;
    return p * q;
  }, [price, quantity]);

  // Quick fill price from order book
  const handlePriceClick = useCallback((newPrice: number) => {
    setPrice(newPrice.toString());
  }, []);

  // Percentage buttons for quantity
  const handlePercentage = useCallback((percent: number) => {
    // In production, this would calculate based on user's balance
    const baseQty = 1; // Example base quantity
    setQuantity((baseQty * percent / 100).toFixed(8));
  }, []);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();

    const priceNum = parseFloat(price);
    const quantityNum = parseFloat(quantity);

    if (orderType === 'LIMIT' && (!priceNum || priceNum <= 0)) {
      setNotification({ type: 'error', message: 'Please enter a valid price' });
      return;
    }

    if (!quantityNum || quantityNum <= 0) {
      setNotification({ type: 'error', message: 'Please enter a valid quantity' });
      return;
    }

    const order: OrderRequest = {
      userId: USER_ID,
      market: market.symbol,
      orderType,
      orderSide: side,
      price: orderType === 'MARKET' ? 0 : priceNum,
      quantity: quantityNum,
      totalPrice: orderType === 'MARKET' && isBuy ? total : undefined,
      timestamp: Date.now(),
    };

    const result = await onSubmitOrder(order);

    if (result.success) {
      setNotification({ type: 'success', message: `${isBuy ? 'Buy' : 'Sell'} order submitted` });
      setPrice('');
      setQuantity('');
    } else {
      setNotification({ type: 'error', message: result.message });
    }

    // Clear notification after 3 seconds
    setTimeout(() => setNotification(null), 3000);
  }, [price, quantity, orderType, side, market.symbol, isBuy, total, onSubmitOrder]);

  return (
    <div className="order-form-container">
      <div className="order-form-header">
        <h3>Place Order</h3>
      </div>

      {/* Side Toggle */}
      <div className="side-toggle">
        <button
          className={`side-btn buy ${side === 'BID' ? 'active' : ''}`}
          onClick={() => setSide('BID')}
        >
          Buy
        </button>
        <button
          className={`side-btn sell ${side === 'ASK' ? 'active' : ''}`}
          onClick={() => setSide('ASK')}
        >
          Sell
        </button>
      </div>

      {/* Order Type */}
      <div className="order-type-tabs">
        <button
          className={`type-tab ${orderType === 'LIMIT' ? 'active' : ''}`}
          onClick={() => setOrderType('LIMIT')}
        >
          Limit
        </button>
        <button
          className={`type-tab ${orderType === 'MARKET' ? 'active' : ''}`}
          onClick={() => setOrderType('MARKET')}
        >
          Market
        </button>
        <button
          className={`type-tab ${orderType === 'LIMIT_MAKER' ? 'active' : ''}`}
          onClick={() => setOrderType('LIMIT_MAKER')}
        >
          Post Only
        </button>
      </div>

      <form onSubmit={handleSubmit} className="order-form">
        {/* Price Input */}
        {orderType !== 'MARKET' && (
          <div className="form-group">
            <label>Price ({market.quoteAsset})</label>
            <div className="input-with-buttons">
              <input
                type="number"
                value={price}
                onChange={(e) => setPrice(e.target.value)}
                placeholder="0.00"
                step="0.01"
                min="0"
              />
              <div className="quick-buttons">
                {bestBid && (
                  <button type="button" onClick={() => handlePriceClick(bestBid.price)} className="quick-btn">
                    Bid
                  </button>
                )}
                {bestAsk && (
                  <button type="button" onClick={() => handlePriceClick(bestAsk.price)} className="quick-btn">
                    Ask
                  </button>
                )}
              </div>
            </div>
          </div>
        )}

        {/* Quantity Input */}
        <div className="form-group">
          <label>Amount ({market.baseAsset})</label>
          <input
            type="number"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            placeholder="0.00000000"
            step="0.00000001"
            min="0"
          />
          <div className="percentage-buttons">
            {[25, 50, 75, 100].map(pct => (
              <button key={pct} type="button" onClick={() => handlePercentage(pct)} className="pct-btn">
                {pct}%
              </button>
            ))}
          </div>
        </div>

        {/* Total */}
        <div className="form-group">
          <label>Total ({market.quoteAsset})</label>
          <div className="total-display">
            ${formatPrice(total)}
          </div>
        </div>

        {/* Notification */}
        {notification && (
          <div className={`notification ${notification.type}`}>
            {notification.message}
          </div>
        )}

        {/* Submit Button */}
        <button
          type="submit"
          className={`submit-btn ${isBuy ? 'buy' : 'sell'}`}
          disabled={loading}
        >
          {loading ? 'Submitting...' : `${isBuy ? 'Buy' : 'Sell'} ${market.baseAsset}`}
        </button>
      </form>

      {/* Market Info */}
      <div className="market-prices">
        <div className="price-row">
          <span className="label">Best Bid:</span>
          <span className="value bid">{bestBid ? `$${formatPrice(bestBid.price)}` : '-'}</span>
        </div>
        <div className="price-row">
          <span className="label">Best Ask:</span>
          <span className="value ask">{bestAsk ? `$${formatPrice(bestAsk.price)}` : '-'}</span>
        </div>
      </div>
    </div>
  );
}
