// Market definitions
export interface Market {
  id: number;
  symbol: string;
  baseAsset: string;
  quoteAsset: string;
}

export const MARKETS: Market[] = [
  { id: 1, symbol: 'BTC-USD', baseAsset: 'BTC', quoteAsset: 'USD' },
  { id: 2, symbol: 'ETH-USD', baseAsset: 'ETH', quoteAsset: 'USD' },
  { id: 3, symbol: 'SOL-USD', baseAsset: 'SOL', quoteAsset: 'USD' },
];

export interface BookLevel {
  price: number;
  quantity: number;
  orderCount: number;
}

export interface Trade {
  tradeId: number;
  takerOrderId: number;
  makerOrderId: number;
  takerUserId: number;
  makerUserId: number;
  price: number;
  quantity: number;
  takerSide: 'BID' | 'ASK';
  timestamp: number;
}

// Aggregated trade from batch message
export interface AggregatedTrade {
  price: number;
  quantity: number;
  tradeCount: number;
  buyCount: number;
  sellCount: number;
  timestamp: number;
}

export interface TradesBatchMessage {
  type: 'TRADES_BATCH';
  marketId: number;
  market: string;
  trades: AggregatedTrade[];
  timestamp: number;
}

export interface BookSnapshotMessage {
  type: 'BOOK_SNAPSHOT';
  marketId: number;
  market: string;
  bids: BookLevel[];
  asks: BookLevel[];
  timestamp: number;
  version?: number;
}

export interface OrderStatusMessage {
  type: 'ORDER_STATUS';
  marketId: number;
  market: string;
  orderId: number;
  userId: number;
  status: OrderStatus;
  price: number;
  remainingQuantity: number;
  filledQuantity: number;
  side: 'BID' | 'ASK';
  timestamp: number;
}

export interface SubscriptionConfirmMessage {
  type: 'SUBSCRIPTION_CONFIRMED';
  status: string;
  marketId: number;
}

export interface PongMessage {
  type: 'PONG';
  timestamp: number;
}

export interface ErrorMessage {
  type: 'ERROR';
  message: string;
}

// Cluster status types
export type NodeStatus = 'LEADER' | 'FOLLOWER' | 'CANDIDATE' | 'OFFLINE' | 'STARTING';

export interface ClusterNode {
  id: number;
  status: NodeStatus;
  healthy: boolean;
}

export interface ClusterStatusMessage {
  type: 'CLUSTER_STATUS';
  leaderId: number;
  leadershipTermId: number;
  nodes: ClusterNode[];
  gatewayConnected: boolean;
  timestamp: number;
}

export interface ClusterEventMessage {
  type: 'CLUSTER_EVENT';
  event: 'LEADER_CHANGE' | 'NODE_UP' | 'NODE_DOWN' | 'ROLLING_UPDATE_START' | 'ROLLING_UPDATE_COMPLETE' | 'CONNECTION_LOST' | 'CONNECTION_RESTORED';
  nodeId?: number;
  newLeaderId?: number;
  message: string;
  timestamp: number;
}

export type WebSocketMessage =
  | TradesBatchMessage
  | BookSnapshotMessage
  | OrderStatusMessage
  | SubscriptionConfirmMessage
  | PongMessage
  | ErrorMessage
  | ClusterStatusMessage
  | ClusterEventMessage;

export interface OrderBook {
  bids: BookLevel[];
  asks: BookLevel[];
  lastUpdate: number;
  spread?: number;
  spreadPercent?: number;
}

export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error';

// Order types
export type OrderSide = 'BID' | 'ASK';
export type OrderType = 'LIMIT' | 'MARKET' | 'LIMIT_MAKER';
export type OrderStatus = 'NEW' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELLED' | 'REJECTED';

export interface UserOrder {
  orderId: number;
  marketId: number;
  market: string;
  userId: number;
  side: OrderSide;
  type: OrderType;
  price: number;
  originalQuantity: number;
  remainingQuantity: number;
  filledQuantity: number;
  status: OrderStatus;
  timestamp: number;
}

export interface OrderRequest {
  userId: string;
  market: string;
  orderType: OrderType;
  orderSide: OrderSide;
  price: number;
  quantity: number;
  totalPrice?: number;
  timestamp: number;
}

// Ticker/Stats
export interface MarketStats {
  lastPrice: number;
  priceChange: number;
  priceChangePercent: number;
  high24h: number;
  low24h: number;
  volume24h: number;
}
