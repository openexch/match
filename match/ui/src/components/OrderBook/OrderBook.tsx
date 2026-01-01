import type { OrderBook as OrderBookType } from '../../types/market';
import { formatPrice, formatQuantity } from '../../utils/formatters';
import './OrderBook.css';

interface OrderBookProps {
  orderBook: OrderBookType;
}

export function OrderBook({ orderBook }: OrderBookProps) {
  const { bids, asks } = orderBook;

  // Calculate max quantity for depth visualization
  const allQuantities = [...bids.map((l) => l.quantity), ...asks.map((l) => l.quantity)];
  const maxQuantity = Math.max(...allQuantities, 1);

  return (
    <div className="order-book">
      <h2>Order Book</h2>
      <div className="order-book-header">
        <span>Price (USD)</span>
        <span>Quantity (BTC)</span>
        <span>Orders</span>
      </div>

      <div className="order-book-asks">
        {asks.slice().reverse().map((level, i) => (
          <div key={`ask-${i}`} className="order-book-row ask">
            <div
              className="depth-bar ask-bar"
              style={{ width: `${(level.quantity / maxQuantity) * 100}%` }}
            />
            <span className="price ask-price">{formatPrice(level.price)}</span>
            <span className="quantity">{formatQuantity(level.quantity)}</span>
            <span className="order-count">{level.orderCount}</span>
          </div>
        ))}
      </div>

      <div className="order-book-spread">
        {bids.length > 0 && asks.length > 0 && (
          <span>Spread: {formatPrice(asks[0].price - bids[0].price)}</span>
        )}
      </div>

      <div className="order-book-bids">
        {bids.map((level, i) => (
          <div key={`bid-${i}`} className="order-book-row bid">
            <div
              className="depth-bar bid-bar"
              style={{ width: `${(level.quantity / maxQuantity) * 100}%` }}
            />
            <span className="price bid-price">{formatPrice(level.price)}</span>
            <span className="quantity">{formatQuantity(level.quantity)}</span>
            <span className="order-count">{level.orderCount}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
