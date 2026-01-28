import { useEffect, useRef, useCallback } from 'react';
import { createChart, CandlestickSeries, HistogramSeries, ColorType } from 'lightweight-charts';
import type { IChartApi, ISeriesApi, CandlestickData, HistogramData, Time } from 'lightweight-charts';
import type { CandleData } from '../../types/market';
import './Chart.css';

interface ChartProps {
  candles: CandleData[];
  currentCandle: CandleData | null;
  symbol: string;
  onIntervalChange: (interval: string) => void;
  activeInterval: string;
}

type Interval = '1m' | '5m' | '15m' | '1h' | '4h' | '1d';

const INTERVALS: Interval[] = ['1m', '5m', '15m', '1h', '4h', '1d'];

export function Chart({ candles, currentCandle, symbol, onIntervalChange, activeInterval }: ChartProps) {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volumeSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const hasInitialFitRef = useRef(false);
  const lastCandleCountRef = useRef(0);

  // Initialize chart (once)
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
        vertLines: { color: '#1e232920' },
        horzLines: { color: '#1e232920' },
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
        scaleMargins: { top: 0.1, bottom: 0.25 },
      },
      timeScale: {
        borderColor: '#222730',
        timeVisible: true,
        secondsVisible: false,
      },
      handleScroll: { vertTouchDrag: false },
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
      priceFormat: { type: 'volume' },
      priceScaleId: 'volume',
    });
    volumeSeries.priceScale().applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    });

    chartRef.current = chart;
    candleSeriesRef.current = candleSeries;
    volumeSeriesRef.current = volumeSeries;

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

  // Push candle history to chart when candles array changes
  useEffect(() => {
    if (!candleSeriesRef.current || !volumeSeriesRef.current) return;
    if (candles.length === 0) {
      // Clear the chart
      candleSeriesRef.current.setData([]);
      volumeSeriesRef.current.setData([]);
      lastCandleCountRef.current = 0;
      hasInitialFitRef.current = false;
      return;
    }

    // Build chart data from server candles (already in ascending time order)
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
    lastCandleCountRef.current = candles.length;

    if (!hasInitialFitRef.current) {
      chartRef.current?.timeScale().fitContent();
      hasInitialFitRef.current = true;
    }
  }, [candles]);

  // Update the current (in-progress) candle in real-time
  useEffect(() => {
    if (!candleSeriesRef.current || !volumeSeriesRef.current || !currentCandle) return;

    candleSeriesRef.current.update({
      time: currentCandle.time as Time,
      open: currentCandle.open,
      high: currentCandle.high,
      low: currentCandle.low,
      close: currentCandle.close,
    });

    volumeSeriesRef.current.update({
      time: currentCandle.time as Time,
      value: currentCandle.volume,
      color: currentCandle.close >= currentCandle.open
        ? 'rgba(14, 203, 129, 0.3)'
        : 'rgba(246, 70, 93, 0.3)',
    });
  }, [currentCandle]);

  // Reset fit on interval change
  useEffect(() => {
    hasInitialFitRef.current = false;
    lastCandleCountRef.current = 0;
  }, [activeInterval]);

  const handleIntervalChange = useCallback((newInterval: Interval) => {
    onIntervalChange(newInterval);
  }, [onIntervalChange]);

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
          {INTERVALS.map(iv => (
            <button
              key={iv}
              className={`interval-btn ${activeInterval === iv ? 'active' : ''}`}
              onClick={() => handleIntervalChange(iv)}
            >
              {iv}
            </button>
          ))}
        </div>
      </div>
      <div className="chart-container" ref={chartContainerRef} />
      {candles.length === 0 && !currentCandle && (
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
