// Market definitions
export interface Market {
  id: number;
  symbol: string;
  baseAsset: string;
  quoteAsset: string;
  name: string;
}

export const MARKETS: Market[] = [
  { id: 1, symbol: 'BTC-USD', baseAsset: 'BTC', quoteAsset: 'USD', name: 'Bitcoin' },
  { id: 2, symbol: 'ETH-USD', baseAsset: 'ETH', quoteAsset: 'USD', name: 'Ethereum' },
  { id: 3, symbol: 'SOL-USD', baseAsset: 'SOL', quoteAsset: 'USD', name: 'Solana' },
  { id: 4, symbol: 'XRP-USD', baseAsset: 'XRP', quoteAsset: 'USD', name: 'Ripple' },
  { id: 5, symbol: 'DOGE-USD', baseAsset: 'DOGE', quoteAsset: 'USD', name: 'Dogecoin' },
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

export interface BookDeltaChange {
  price: number;
  quantity: number;
  orderCount: number;
  side: 'BID' | 'ASK';
  updateType: 'NEW_LEVEL' | 'UPDATE_LEVEL' | 'DELETE_LEVEL';
}

export interface BookDeltaMessage {
  type: 'BOOK_DELTA';
  marketId: number;
  market: string;
  changes: BookDeltaChange[];
  bidVersion: number;
  askVersion: number;
  timestamp: number;
}

export interface OrderStatusMessage {
  type?: 'ORDER_STATUS';
  marketId?: number;
  market?: string;
  orderId: number;
  userId: number;
  status: OrderStatus;
  price: number;
  remainingQuantity: number;
  filledQuantity: number;
  side: 'BID' | 'ASK';
  timestamp: number;
}

export interface OrderStatusBatchMessage {
  type: 'ORDER_STATUS_BATCH';
  marketId: number;
  market: string;
  orders: OrderStatusMessage[];
  count: number;
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

export interface TickerStatsMessage {
  type: 'TICKER_STATS';
  marketId: number;
  market: string;
  lastPrice: number;
  priceChange: number;
  priceChangePercent: number;
  high24h: number;
  low24h: number;
  volume24h: number;
  timestamp: number;
}

// Cluster status types - includes transitional states during rolling updates
export type NodeStatus = 'LEADER' | 'FOLLOWER' | 'CANDIDATE' | 'OFFLINE' | 'STOPPING' | 'STARTING' | 'REJOINING' | 'ELECTION';

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

// UI cluster state - aggregated from CLUSTER_STATUS and CLUSTER_EVENT messages
export interface ClusterState {
  leaderId: number;
  leadershipTermId: number;
  nodes: ClusterNode[];
  gatewayConnected: boolean;
  isRollingUpdate: boolean;
  isElecting: boolean;
  lastEvent: ClusterEventMessage | null;
  lastUpdate: number;
}

// Extended connection status that includes cluster-aware states
export type ExtendedConnectionStatus =
  | 'connecting'
  | 'connected'
  | 'disconnected'
  | 'error'
  | 'cluster-updating'
  | 'cluster-electing'
  | 'cluster-reconnecting';

// Candle types (server-aggregated)
export interface CandleData {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  tradeCount: number;
}

export interface CandleHistoryMessage {
  type: 'CANDLE_HISTORY';
  marketId: number;
  market: string;
  interval: string;
  candles: CandleData[];
}

export interface CandleUpdateMessage {
  type: 'CANDLE_UPDATE';
  marketId: number;
  market: string;
  interval: string;
  candle: CandleData;
}

export type WebSocketMessage =
  | TradesBatchMessage
  | BookSnapshotMessage
  | BookDeltaMessage
  | OrderStatusMessage
  | OrderStatusBatchMessage
  | SubscriptionConfirmMessage
  | PongMessage
  | ErrorMessage
  | TickerStatsMessage
  | ClusterStatusMessage
  | ClusterEventMessage
  | CandleHistoryMessage
  | CandleUpdateMessage;

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
