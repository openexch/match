package main

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"github.com/gorilla/websocket"
)

// ConnectTradeStream connects to Binance trade stream and sends trades to channel
func ConnectTradeStream(ctx context.Context, url string, trades chan<- BinanceTrade, metrics *Metrics, market string) {
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		log.Printf("[%s] Connecting to Binance trade stream: %s", market, url)
		conn, _, err := websocket.DefaultDialer.Dial(url, nil)
		if err != nil {
			log.Printf("[%s] Trade stream connection error: %v, retrying in 5s...", market, err)
			time.Sleep(5 * time.Second)
			continue
		}

		log.Printf("[%s] Connected to Binance trade stream", market)
		metrics.SetConnected(market, true)

		// Read messages until error
		for {
			select {
			case <-ctx.Done():
				conn.Close()
				return
			default:
			}

			_, msg, err := conn.ReadMessage()
			if err != nil {
				log.Printf("[%s] Trade stream read error: %v, reconnecting...", market, err)
				metrics.SetConnected(market, false)
				conn.Close()
				break
			}

			var trade BinanceTrade
			if err := json.Unmarshal(msg, &trade); err != nil {
				log.Printf("[%s] Trade parse error: %v", market, err)
				continue
			}

			// Non-blocking send to avoid blocking on slow consumers
			select {
			case trades <- trade:
				metrics.IncrementBinanceTrades(market)
			default:
				// Channel full, drop trade
				metrics.IncrementDropped(market)
			}
		}

		time.Sleep(time.Second) // Brief pause before reconnect
	}
}

// ConnectBookTicker connects to Binance book ticker stream
func ConnectBookTicker(ctx context.Context, url string, tickers chan<- BinanceBookTicker, metrics *Metrics, market string) {
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		log.Printf("[%s] Connecting to Binance book ticker: %s", market, url)
		conn, _, err := websocket.DefaultDialer.Dial(url, nil)
		if err != nil {
			log.Printf("[%s] Book ticker connection error: %v, retrying in 5s...", market, err)
			time.Sleep(5 * time.Second)
			continue
		}

		log.Printf("[%s] Connected to Binance book ticker", market)

		// Read messages until error
		for {
			select {
			case <-ctx.Done():
				conn.Close()
				return
			default:
			}

			_, msg, err := conn.ReadMessage()
			if err != nil {
				log.Printf("[%s] Book ticker read error: %v, reconnecting...", market, err)
				conn.Close()
				break
			}

			var ticker BinanceBookTicker
			if err := json.Unmarshal(msg, &ticker); err != nil {
				log.Printf("[%s] Book ticker parse error: %v", market, err)
				continue
			}

			// Non-blocking send
			select {
			case tickers <- ticker:
			default:
				// Channel full, drop ticker (they come frequently)
			}
		}

		time.Sleep(time.Second)
	}
}
