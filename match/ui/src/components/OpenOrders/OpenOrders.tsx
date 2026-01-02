import type { UserOrder } from '../../types/market';
import { formatPrice, formatQuantity, formatTime } from '../../utils/formatters';
import './OpenOrders.css';

interface OpenOrdersProps {
  orders: UserOrder[];
  onCancelOrder: (orderId: number) => void;
  loading: boolean;
}

export function OpenOrders({ orders, onCancelOrder, loading }: OpenOrdersProps) {
  const filledPercent = (order: UserOrder) => {
    const total = order.originalQuantity;
    if (total === 0) return 0;
    return (order.filledQuantity / total) * 100;
  };

  return (
    <div className="open-orders">
      <div className="open-orders-header">
        <h3>Open Orders</h3>
        <span className="order-count">{orders.length}</span>
      </div>

      {orders.length === 0 ? (
        <div className="empty-state">
          <span>No open orders</span>
        </div>
      ) : (
        <div className="orders-table-wrapper">
          <table className="orders-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Market</th>
                <th>Side</th>
                <th>Price</th>
                <th>Amount</th>
                <th>Filled</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {orders.map(order => (
                <tr key={order.orderId}>
                  <td className="time">{formatTime(order.timestamp)}</td>
                  <td className="market">{order.market}</td>
                  <td className={`side ${order.side === 'BID' ? 'bid' : 'ask'}`}>
                    {order.side === 'BID' ? 'Buy' : 'Sell'}
                  </td>
                  <td className="price">${formatPrice(order.price)}</td>
                  <td className="amount">{formatQuantity(order.originalQuantity)}</td>
                  <td className="filled">
                    <div className="filled-bar-container">
                      <div
                        className="filled-bar"
                        style={{ width: `${filledPercent(order)}%` }}
                      />
                      <span>{filledPercent(order).toFixed(1)}%</span>
                    </div>
                  </td>
                  <td className={`status ${order.status.toLowerCase()}`}>
                    {order.status === 'PARTIALLY_FILLED' ? 'Partial' : order.status}
                  </td>
                  <td className="actions">
                    <button
                      className="cancel-btn"
                      onClick={() => onCancelOrder(order.orderId)}
                      disabled={loading}
                      title="Cancel order"
                    >
                      Cancel
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
