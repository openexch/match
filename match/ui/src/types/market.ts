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

export interface TradesBatchMessage {
  type: 'TRADES_BATCH';
  marketId: number;
  market: string;
  trades: Trade[];
  timestamp: number;
}

export interface BookSnapshotMessage {
  type: 'BOOK_SNAPSHOT';
  marketId: number;
  market: string;
  bids: BookLevel[];
  asks: BookLevel[];
  timestamp: number;
}

export interface OrderStatusMessage {
  type: 'ORDER_STATUS';
  marketId: number;
  market: string;
  orderId: number;
  userId: number;
  status: string;
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

export type WebSocketMessage =
  | TradesBatchMessage
  | BookSnapshotMessage
  | OrderStatusMessage
  | SubscriptionConfirmMessage
  | PongMessage
  | ErrorMessage;

export interface OrderBook {
  bids: BookLevel[];
  asks: BookLevel[];
  lastUpdate: number;
}

export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error';
