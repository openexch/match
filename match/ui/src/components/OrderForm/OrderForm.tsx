import { useState, useCallback, useMemo, useEffect } from 'react';
import type { Market, OrderSide, OrderType, OrderRequest } from '../../types/market';
import { formatPrice } from '../../utils/formatters';
import './OrderForm.css';

interface OrderFormProps {
  market: Market;
  bestBid?: unknown;
  bestAsk?: unknown;
  onSubmitOrder: (order: OrderRequest) => Promise<{ success: boolean; message: string }>;
  loading: boolean;
  externalPrice?: number | null;
  isMobile?: boolean;
  defaultSide?: 'BID' | 'ASK';
}

const USER_ID = '1';

function OrderSideForm({
  side,
  market,
  orderType,
  onSubmitOrder,
  loading,
  externalPrice,
}: {
  side: OrderSide;
  market: Market;
  orderType: OrderType;
  onSubmitOrder: (order: OrderRequest) => Promise<{ success: boolean; message: string }>;
  loading: boolean;
  externalPrice?: number | null;
}) {
  const [price, setPrice] = useState('');
  const [quantity, setQuantity] = useState('');
  const [notification, setNotification] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const [sliderValue, setSliderValue] = useState(0);

  const isBuy = side === 'BID';

  // Sync external price from order book click
  useEffect(() => {
    if (externalPrice !== null && externalPrice !== undefined) {
      setPrice(externalPrice.toString());
    }
  }, [externalPrice]);

  const total = useMemo(() => {
    const p = parseFloat(price) || 0;
    const q = parseFloat(quantity) || 0;
    return p * q;
  }, [price, quantity]);

  const handlePercentage = useCallback((percent: number) => {
    setSliderValue(percent);
    const baseQty = 1;
    setQuantity((baseQty * percent / 100).toFixed(8));
  }, []);

  const handleSlider = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const pct = parseInt(e.target.value);
    setSliderValue(pct);
    const baseQty = 1;
    setQuantity((baseQty * pct / 100).toFixed(8));
  }, []);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();

    const priceNum = parseFloat(price);
    const quantityNum = parseFloat(quantity);

    if (orderType !== 'MARKET' && (!priceNum || priceNum <= 0)) {
      setNotification({ type: 'error', message: 'Enter a valid price' });
      return;
    }

    if (!quantityNum || quantityNum <= 0) {
      setNotification({ type: 'error', message: 'Enter a valid amount' });
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
      setNotification({ type: 'success', message: `${isBuy ? 'Buy' : 'Sell'} order placed` });
      setPrice('');
      setQuantity('');
      setSliderValue(0);
    } else {
      setNotification({ type: 'error', message: result.message });
    }

    setTimeout(() => setNotification(null), 3000);
  }, [price, quantity, orderType, side, market.symbol, isBuy, total, onSubmitOrder]);

  return (
    <form onSubmit={handleSubmit} className={`order-side-form ${isBuy ? 'buy-form' : 'sell-form'}`}>
      {/* Price Input — always rendered to keep form height stable */}
      <div className={`form-group ${orderType === 'MARKET' ? 'form-group-hidden' : ''}`}>
        <label>Price</label>
        <div className="input-wrapper">
          <input
            type="number"
            value={orderType === 'MARKET' ? '' : price}
            onChange={(e) => setPrice(e.target.value)}
            placeholder={orderType === 'MARKET' ? 'Market' : '0.00'}
            step="0.01"
            min="0"
            disabled={orderType === 'MARKET'}
            tabIndex={orderType === 'MARKET' ? -1 : undefined}
          />
          <span className="input-suffix">{market.quoteAsset}</span>
        </div>
      </div>

      {/* Amount Input */}
      <div className="form-group">
        <label>Amount</label>
        <div className="input-wrapper">
          <input
            type="number"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            placeholder="0.00"
            step="0.00000001"
            min="0"
          />
          <span className="input-suffix">{market.baseAsset}</span>
        </div>
      </div>

      {/* Slider */}
      <div className="slider-group">
        <input
          type="range"
          min="0"
          max="100"
          value={sliderValue}
          onChange={handleSlider}
          className={`amount-slider ${isBuy ? 'buy-slider' : 'sell-slider'}`}
        />
        <div className="slider-marks">
          {[0, 25, 50, 75, 100].map(pct => (
            <button
              key={pct}
              type="button"
              onClick={() => handlePercentage(pct)}
              className={`slider-mark ${sliderValue >= pct ? 'active' : ''}`}
            />
          ))}
        </div>
      </div>

      {/* Total */}
      <div className="form-group">
        <label>Total</label>
        <div className="total-display">
          <span>{formatPrice(total)}</span>
          <span className="input-suffix">{market.quoteAsset}</span>
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
        {loading ? '...' : `${isBuy ? 'Buy' : 'Sell'} ${market.baseAsset}`}
      </button>
    </form>
  );
}

export function OrderForm({ market, onSubmitOrder, loading, externalPrice, isMobile, defaultSide }: OrderFormProps) {
  const [orderType, setOrderType] = useState<OrderType>('LIMIT');
  const [mobileSide, setMobileSide] = useState<'BID' | 'ASK'>(defaultSide || 'BID');

  // Sync mobileSide when defaultSide changes (e.g. opening from Buy/Sell button)
  useEffect(() => {
    if (defaultSide) setMobileSide(defaultSide);
  }, [defaultSide]);

  return (
    <div className="order-form-container">
      {/* Mobile Buy/Sell side toggle */}
      {isMobile && (
        <div className="mobile-side-toggle">
          <button
            className={`side-toggle-btn buy ${mobileSide === 'BID' ? 'active' : ''}`}
            onClick={() => setMobileSide('BID')}
          >
            Buy
          </button>
          <button
            className={`side-toggle-btn sell ${mobileSide === 'ASK' ? 'active' : ''}`}
            onClick={() => setMobileSide('ASK')}
          >
            Sell
          </button>
        </div>
      )}

      {/* Shared Order Type Tabs */}
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

      {/* Side-by-side Buy/Sell Forms (desktop) or single form (mobile) */}
      <div className={`order-forms-row ${isMobile ? `mobile-side-${mobileSide === 'BID' ? 'buy' : 'sell'}` : ''}`}>
        <OrderSideForm
          side="BID"
          market={market}
          orderType={orderType}
          onSubmitOrder={onSubmitOrder}
          loading={loading}
          externalPrice={externalPrice}
        />
        <div className="form-divider" />
        <OrderSideForm
          side="ASK"
          market={market}
          orderType={orderType}
          onSubmitOrder={onSubmitOrder}
          loading={loading}
          externalPrice={externalPrice}
        />
      </div>
    </div>
  );
}
