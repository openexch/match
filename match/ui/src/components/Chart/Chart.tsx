import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { createChart, CandlestickSeries, HistogramSeries, ColorType } from 'lightweight-charts';
import type { IChartApi, ISeriesApi, CandlestickData, HistogramData, Time } from 'lightweight-charts';
import type { AggregatedTrade } from '../../types/market';
import './Chart.css';

interface ChartProps {
  trades: AggregatedTrade[];
  symbol: string;
}

type Interval = '1m' | '5m' | '15m' | '1h' | '4h' | '1d';

const INTERVAL_MS: Record<Interval, number> = {
  '1m': 60_000,
  '5m': 300_000,
  '15m': 900_000,
  '1h': 3_600_000,
  '4h': 14_400_000,
  '1d': 86_400_000,
};

interface Candle {
  time: number; // unix seconds
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

function buildCandles(trades: AggregatedTrade[], intervalMs: number): Candle[] {
  if (trades.length === 0) return [];

  const candleMap = new Map<number, Candle>();

  // Process trades oldest-first
  const sorted = [...trades].sort((a, b) => a.timestamp - b.timestamp);

  for (const trade of sorted) {
    const bucket = Math.floor(trade.timestamp / intervalMs) * intervalMs;
    const timeSec = Math.floor(bucket / 1000);

    const existing = candleMap.get(timeSec);
    if (existing) {
      existing.high = Math.max(existing.high, trade.price);
      existing.low = Math.min(existing.low, trade.price);
      existing.close = trade.price;
      existing.volume += trade.quantity;
    } else {
      candleMap.set(timeSec, {
        time: timeSec,
        open: trade.price,
        high: trade.price,
        low: trade.price,
        close: trade.price,
        volume: trade.quantity,
      });
    }
  }

  return Array.from(candleMap.values()).sort((a, b) => a.time - b.time);
}

export function Chart({ trades, symbol }: ChartProps) {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volumeSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const [interval, setInterval] = useState<Interval>('1m');
  const prevTradesLengthRef = useRef(0);

  // Build candles from trades
  const candles = useMemo(() => buildCandles(trades, INTERVAL_MS[interval]), [trades, interval]);

  // Initialize chart
  useEffect(() => {
    if (!chartContainerRef.current) return;

    const chart = createChart(chartContainerRef.current, {
      layout: {
        background: { type: ColorType.Solid, color: '#0b0e11' },
        textColor: '#848e9c',
        fontFamily: "'JetBrains Mono', 'SF Mono', Monaco, monospace",
        fontSize: 11,
      },
      grid: {
        vertLines: { color: '#1e232930' },
        horzLines: { color: '#1e232930' },
      },
      crosshair: {
        vertLine: {
          color: '#848e9c40',
          labelBackgroundColor: '#1e2329',
        },
        horzLine: {
          color: '#848e9c40',
          labelBackgroundColor: '#1e2329',
        },
      },
      rightPriceScale: {
        borderColor: '#222730',
        scaleMargins: {
          top: 0.1,
          bottom: 0.25,
        },
      },
      timeScale: {
        borderColor: '#222730',
        timeVisible: true,
        secondsVisible: false,
      },
      handleScroll: {
        vertTouchDrag: false,
      },
    });

    const candleSeries = chart.addSeries(CandlestickSeries, {
      upColor: '#0ecb81',
      downColor: '#f6465d',
      borderUpColor: '#0ecb81',
      borderDownColor: '#f6465d',
      wickUpColor: '#0ecb81',
      wickDownColor: '#f6465d',
    });

    const volumeSeries = chart.addSeries(HistogramSeries, {
      priceFormat: {
        type: 'volume',
      },
      priceScaleId: 'volume',
    });

    // Configure volume overlay to occupy bottom 20% of chart
    volumeSeries.priceScale().applyOptions({
      scaleMargins: {
        top: 0.8,
        bottom: 0,
      },
    });

    chartRef.current = chart;
    candleSeriesRef.current = candleSeries;
    volumeSeriesRef.current = volumeSeries;

    // Handle resize
    const handleResize = () => {
      if (chartContainerRef.current) {
        chart.applyOptions({
          width: chartContainerRef.current.clientWidth,
          height: chartContainerRef.current.clientHeight,
        });
      }
    };

    const resizeObserver = new ResizeObserver(handleResize);
    resizeObserver.observe(chartContainerRef.current);

    return () => {
      resizeObserver.disconnect();
      chart.remove();
      chartRef.current = null;
      candleSeriesRef.current = null;
      volumeSeriesRef.current = null;
    };
  }, []);

  // Update chart data when candles change
  useEffect(() => {
    if (!candleSeriesRef.current || !volumeSeriesRef.current) return;

    const candleData: CandlestickData[] = candles.map(c => ({
      time: c.time as Time,
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    }));

    const volumeData: HistogramData[] = candles.map(c => ({
      time: c.time as Time,
      value: c.volume,
      color: c.close >= c.open ? 'rgba(14, 203, 129, 0.3)' : 'rgba(246, 70, 93, 0.3)',
    }));

    candleSeriesRef.current.setData(candleData);
    volumeSeriesRef.current.setData(volumeData);

    // Only auto-fit on first data load or interval change
    if (prevTradesLengthRef.current === 0 && candles.length > 0) {
      chartRef.current?.timeScale().fitContent();
    }
    prevTradesLengthRef.current = trades.length;
  }, [candles, trades.length]);

  const handleIntervalChange = useCallback((newInterval: Interval) => {
    setInterval(newInterval);
    prevTradesLengthRef.current = 0; // trigger re-fit
  }, []);

  const intervals: Interval[] = ['1m', '5m', '15m', '1h', '4h', '1d'];

  return (
    <div className="chart-component">
      <div className="chart-toolbar">
        <div className="chart-title">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M3 3v18h18"/>
            <path d="M7 14l4-4 4 4 5-5"/>
          </svg>
          <span>{symbol}</span>
        </div>
        <div className="interval-tabs">
          {intervals.map(iv => (
            <button
              key={iv}
              className={`interval-btn ${interval === iv ? 'active' : ''}`}
              onClick={() => handleIntervalChange(iv)}
            >
              {iv}
            </button>
          ))}
        </div>
      </div>
      <div className="chart-container" ref={chartContainerRef} />
      {candles.length === 0 && (
        <div className="chart-empty">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M3 3v18h18" strokeLinecap="round" strokeLinejoin="round"/>
            <rect x="7" y="8" width="2" height="8" rx="0.5" strokeLinejoin="round"/>
            <rect x="11" y="5" width="2" height="11" rx="0.5" strokeLinejoin="round"/>
            <rect x="15" y="10" width="2" height="6" rx="0.5" strokeLinejoin="round"/>
          </svg>
          <span>Waiting for trade data...</span>
        </div>
      )}
    </div>
  );
}
