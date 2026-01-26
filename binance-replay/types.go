package main

import (
	"strconv"
)

// BinanceTrade represents a trade event from Binance WebSocket stream
type BinanceTrade struct {
	EventType  string `json:"e"` // "trade"
	EventTime  int64  `json:"E"` // timestamp ms
	Symbol     string `json:"s"` // "BTCUSDT"
	TradeID    int64  `json:"t"`
	Price      string `json:"p"` // "97123.45"
	Quantity   string `json:"q"` // "0.123"
	BuyerID    int64  `json:"b"` // buyer order ID
	SellerID   int64  `json:"a"` // seller order ID
	TradeTime  int64  `json:"T"` // trade timestamp
	BuyerMaker bool   `json:"m"` // true if buyer was maker
}

// GetPrice parses price string to float64
func (t *BinanceTrade) GetPrice() float64 {
	p, _ := strconv.ParseFloat(t.Price, 64)
	return p
}

// GetQuantity parses quantity string to float64
func (t *BinanceTrade) GetQuantity() float64 {
	q, _ := strconv.ParseFloat(t.Quantity, 64)
	return q
}

// BinanceBookTicker represents a book ticker event from Binance WebSocket
type BinanceBookTicker struct {
	UpdateID int64  `json:"u"` // update ID
	Symbol   string `json:"s"` // "BTCUSDT"
	BidPrice string `json:"b"` // best bid price
	BidQty   string `json:"B"` // best bid quantity
	AskPrice string `json:"a"` // best ask price
	AskQty   string `json:"A"` // best ask quantity
}

// GetBidPrice parses bid price string to float64
func (b *BinanceBookTicker) GetBidPrice() float64 {
	p, _ := strconv.ParseFloat(b.BidPrice, 64)
	return p
}

// GetAskPrice parses ask price string to float64
func (b *BinanceBookTicker) GetAskPrice() float64 {
	p, _ := strconv.ParseFloat(b.AskPrice, 64)
	return p
}

// GetMidPrice returns the mid-point between bid and ask
func (b *BinanceBookTicker) GetMidPrice() float64 {
	return (b.GetBidPrice() + b.GetAskPrice()) / 2.0
}

// Order represents an order to submit to the matching engine
type Order struct {
	UserID     string  `json:"userId"`
	Market     string  `json:"market"`
	OrderType  string  `json:"orderType"`  // LIMIT, MARKET
	OrderSide  string  `json:"orderSide"`  // BID, ASK
	Price      float64 `json:"price"`
	Quantity   float64 `json:"quantity"`
	TotalPrice float64 `json:"totalPrice"`
}
