import { useEffect, useRef, useCallback, useState, useMemo } from 'react';
import {
  createChart,
  CandlestickSeries,
  LineSeries,
  AreaSeries,
  HistogramSeries,
  ColorType,
  LineStyle,
  CrosshairMode,
} from 'lightweight-charts';
import type {
  IChartApi,
  ISeriesApi,
  CandlestickData,
  HistogramData,
  LineData,
  Time,
  MouseEventParams,
  IPriceLine,
} from 'lightweight-charts';
import type { CandleData } from '../../types/market';
import './Chart.css';

// ── Types ──────────────────────────────────────────────────────
interface ChartProps {
  candles: CandleData[];
  currentCandle: CandleData | null;
  symbol: string;
  onIntervalChange: (interval: string) => void;
  activeInterval: string;
}

type Interval = '1m' | '5m' | '15m' | '1h' | '4h' | '1d';
type ChartType = 'candle' | 'line' | 'area';

interface OhlcvDisplay {
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  change: number;
  changePct: number;
  isUp: boolean;
}

interface MaValues {
  ma7: number | null;
  ma25: number | null;
  ma99: number | null;
}

// ── Constants ──────────────────────────────────────────────────
const INTERVALS: Interval[] = ['1m', '5m', '15m', '1h', '4h', '1d'];

const MA_COLORS = {
  ma7: '#f5c842',
  ma25: '#d946ef',
  ma99: '#22d3ee',
} as const;

const UP_COLOR = '#0ecb81';
const DOWN_COLOR = '#f6465d';

// Throttle chart updates to avoid overwhelming the browser under high load
const UPDATE_THROTTLE_MS = 100;

// ── Helpers ────────────────────────────────────────────────────
function calcMA(data: CandleData[], period: number): (LineData | null)[] {
  const result: (LineData | null)[] = [];
  for (let i = 0; i < data.length; i++) {
    if (i < period - 1) {
      result.push(null);
    } else {
      let sum = 0;
      for (let j = i - period + 1; j <= i; j++) {
        sum += data[j].close;
      }
      result.push({
        time: data[i].time as Time,
        value: sum / period,
      });
    }
  }
  return result;
}

function calcVolumeMA(data: CandleData[], period: number): (LineData | null)[] {
  const result: (LineData | null)[] = [];
  for (let i = 0; i < data.length; i++) {
    if (i < period - 1) {
      result.push(null);
    } else {
      let sum = 0;
      for (let j = i - period + 1; j <= i; j++) {
        sum += data[j].volume;
      }
      result.push({
        time: data[i].time as Time,
        value: sum / period,
      });
    }
  }
  return result;
}

function formatPrice(v: number | undefined | null): string {
  const n = v ?? 0;
  if (n >= 1000) return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  if (n >= 1) return n.toFixed(4);
  return n.toFixed(6);
}

function formatVolume(v: number | undefined | null): string {
  const n = v ?? 0;
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(2) + 'K';
  return n.toFixed(2);
}

function getMaValueAtIndex(maData: (LineData | null)[], index: number): number | null {
  if (index < 0 || index >= maData.length) return null;
  return maData[index]?.value ?? null;
}

function isValidCandle(c: CandleData | null): c is CandleData {
  if (!c) return false;
  return (
    c.time != null &&
    c.open != null && !isNaN(c.open) && isFinite(c.open) &&
    c.high != null && !isNaN(c.high) && isFinite(c.high) &&
    c.low != null && !isNaN(c.low) && isFinite(c.low) &&
    c.close != null && !isNaN(c.close) && isFinite(c.close) &&
    c.volume != null && !isNaN(c.volume) && isFinite(c.volume)
  );
}

// ── Component ──────────────────────────────────────────────────
export function Chart({ candles, currentCandle, symbol, onIntervalChange, activeInterval }: ChartProps) {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);

  // Main series refs
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const lineSeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const areaSeriesRef = useRef<ISeriesApi<'Area'> | null>(null);
  const volumeSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null);

  // MA series refs
  const ma7Ref = useRef<ISeriesApi<'Line'> | null>(null);
  const ma25Ref = useRef<ISeriesApi<'Line'> | null>(null);
  const ma99Ref = useRef<ISeriesApi<'Line'> | null>(null);
  const volMaRef = useRef<ISeriesApi<'Line'> | null>(null);

  // Price line ref
  const priceLineRef = useRef<IPriceLine | null>(null);

  // Throttle ref for real-time updates
  const lastUpdateRef = useRef<number>(0);
  const pendingUpdateRef = useRef<CandleData | null>(null);
  const updateTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // MA data cache for crosshair lookup
  const maDataRef = useRef<{ ma7: (LineData | null)[]; ma25: (LineData | null)[]; ma99: (LineData | null)[] }>({
    ma7: [], ma25: [], ma99: [],
  });

  const hasInitialFitRef = useRef(false);
  const lastCandleCountRef = useRef(0);

  // State
  const [chartType, setChartType] = useState<ChartType>('candle');
  const [ohlcv, setOhlcv] = useState<OhlcvDisplay | null>(null);
  const [maValues, setMaValues] = useState<MaValues>({ ma7: null, ma25: null, ma99: null });

  // Compute last candle's OHLCV for default display
  const lastOhlcv = useMemo<OhlcvDisplay | null>(() => {
    const src = currentCandle || (candles.length > 0 ? candles[candles.length - 1] : null);
    if (!isValidCandle(src)) return null;
    const change = src.close - src.open;
    const changePct = src.open !== 0 ? (change / src.open) * 100 : 0;
    return {
      open: src.open,
      high: src.high,
      low: src.low,
      close: src.close,
      volume: src.volume,
      change,
      changePct,
      isUp: src.close >= src.open,
    };
  }, [candles, currentCandle]);

  // Compute last MA values for default display
  const lastMaValues = useMemo<MaValues>(() => {
    if (candles.length === 0) return { ma7: null, ma25: null, ma99: null };
    const ma7Data = calcMA(candles, 7);
    const ma25Data = calcMA(candles, 25);
    const ma99Data = calcMA(candles, 99);
    return {
      ma7: getMaValueAtIndex(ma7Data, ma7Data.length - 1),
      ma25: getMaValueAtIndex(ma25Data, ma25Data.length - 1),
      ma99: getMaValueAtIndex(ma99Data, ma99Data.length - 1),
    };
  }, [candles]);

  // Display values: crosshair data or defaults
  const displayOhlcv = ohlcv || lastOhlcv;
  const displayMa = ohlcv ? maValues : lastMaValues;

  // ── Initialize chart (once) ──
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
        vertLines: { color: '#1e232918' },
        horzLines: { color: '#1e232918' },
      },
      crosshair: {
        mode: CrosshairMode.Normal,
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
        scaleMargins: { top: 0.05, bottom: 0.25 },
        autoScale: true,
      },
      timeScale: {
        borderColor: '#222730',
        timeVisible: true,
        secondsVisible: false,
        barSpacing: 8,
        minBarSpacing: 2,
        rightOffset: 5,
      },
      handleScroll: { vertTouchDrag: false },
    });

    chartRef.current = chart;

    // Volume series (always present, under everything)
    const volumeSeries = chart.addSeries(HistogramSeries, {
      priceFormat: { type: 'volume' },
      priceScaleId: 'volume',
    });
    volumeSeries.priceScale().applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    });
    volumeSeriesRef.current = volumeSeries;

    // Volume MA
    const volMa = chart.addSeries(LineSeries, {
      color: '#848e9c',
      lineWidth: 1,
      priceScaleId: 'volume',
      lastValueVisible: false,
      priceLineVisible: false,
      crosshairMarkerVisible: false,
    });
    volMaRef.current = volMa;

    // ── Crosshair handler ──
    chart.subscribeCrosshairMove((param: MouseEventParams) => {
      if (!param.time || !param.seriesData) {
        setOhlcv(null);
        setMaValues({ ma7: null, ma25: null, ma99: null });
        return;
      }

      // Find candle data from whatever main series is active
      let candleItem: CandlestickData | null = null;
      if (candleSeriesRef.current) {
        const d = param.seriesData.get(candleSeriesRef.current);
        if (d && 'open' in d) candleItem = d as CandlestickData;
      }

      // For line/area mode, look up from source candles array
      if (!candleItem) {
        // Find candle by time
        const t = param.time;
        const found = candles.find(c => c.time === t);
        if (found) {
          candleItem = {
            time: found.time as Time,
            open: found.open,
            high: found.high,
            low: found.low,
            close: found.close,
          };
        }
      }

      if (candleItem) {
        const volData = param.seriesData.get(volumeSeriesRef.current!);
        const vol = volData && 'value' in volData ? (volData as { value: number }).value : 0;
        const change = candleItem.close - candleItem.open;
        const changePct = candleItem.open !== 0 ? (change / candleItem.open) * 100 : 0;
        setOhlcv({
          open: candleItem.open,
          high: candleItem.high,
          low: candleItem.low,
          close: candleItem.close,
          volume: vol,
          change,
          changePct,
          isUp: candleItem.close >= candleItem.open,
        });
      }

      // MA values at crosshair position
      const timeVal = param.time;
      const { ma7, ma25, ma99 } = maDataRef.current;
      const findMaVal = (arr: (LineData | null)[]) => {
        const item = arr.find(d => d && d.time === timeVal);
        return item ? item.value : null;
      };
      setMaValues({
        ma7: findMaVal(ma7),
        ma25: findMaVal(ma25),
        ma99: findMaVal(ma99),
      });
    });

    // ResizeObserver
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
      lineSeriesRef.current = null;
      areaSeriesRef.current = null;
      volumeSeriesRef.current = null;
      ma7Ref.current = null;
      ma25Ref.current = null;
      ma99Ref.current = null;
      volMaRef.current = null;
      priceLineRef.current = null;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Create/recreate main series when chart type changes ──
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;

    // Remove old main series
    if (candleSeriesRef.current) {
      chart.removeSeries(candleSeriesRef.current);
      candleSeriesRef.current = null;
    }
    if (lineSeriesRef.current) {
      chart.removeSeries(lineSeriesRef.current);
      lineSeriesRef.current = null;
    }
    if (areaSeriesRef.current) {
      chart.removeSeries(areaSeriesRef.current);
      areaSeriesRef.current = null;
    }

    // Remove old MA series
    if (ma7Ref.current) { chart.removeSeries(ma7Ref.current); ma7Ref.current = null; }
    if (ma25Ref.current) { chart.removeSeries(ma25Ref.current); ma25Ref.current = null; }
    if (ma99Ref.current) { chart.removeSeries(ma99Ref.current); ma99Ref.current = null; }

    priceLineRef.current = null;

    // Create new main series
    if (chartType === 'candle') {
      const s = chart.addSeries(CandlestickSeries, {
        upColor: UP_COLOR,
        downColor: DOWN_COLOR,
        borderUpColor: UP_COLOR,
        borderDownColor: DOWN_COLOR,
        wickUpColor: UP_COLOR,
        wickDownColor: DOWN_COLOR,
      });
      candleSeriesRef.current = s;
    } else if (chartType === 'line') {
      const s = chart.addSeries(LineSeries, {
        color: '#2962ff',
        lineWidth: 2,
        crosshairMarkerVisible: true,
        crosshairMarkerRadius: 4,
        crosshairMarkerBackgroundColor: '#2962ff',
      });
      lineSeriesRef.current = s;
    } else {
      const s = chart.addSeries(AreaSeries, {
        topColor: 'rgba(41, 98, 255, 0.4)',
        bottomColor: 'rgba(41, 98, 255, 0.02)',
        lineColor: '#2962ff',
        lineWidth: 2,
        crosshairMarkerVisible: true,
      });
      areaSeriesRef.current = s;
    }

    // Recreate MA series (on top of main series)
    const ma7Series = chart.addSeries(LineSeries, {
      color: MA_COLORS.ma7,
      lineWidth: 1,
      lastValueVisible: false,
      priceLineVisible: false,
      crosshairMarkerVisible: false,
    });
    ma7Ref.current = ma7Series;

    const ma25Series = chart.addSeries(LineSeries, {
      color: MA_COLORS.ma25,
      lineWidth: 1,
      lastValueVisible: false,
      priceLineVisible: false,
      crosshairMarkerVisible: false,
    });
    ma25Ref.current = ma25Series;

    const ma99Series = chart.addSeries(LineSeries, {
      color: MA_COLORS.ma99,
      lineWidth: 1,
      lastValueVisible: false,
      priceLineVisible: false,
      crosshairMarkerVisible: false,
    });
    ma99Ref.current = ma99Series;

    // Force data reload
    hasInitialFitRef.current = false;
    lastCandleCountRef.current = 0;
  }, [chartType]);

  // ── Push candle data to chart ──
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    if (!volumeSeriesRef.current) return;

    const mainSeries = candleSeriesRef.current || lineSeriesRef.current || areaSeriesRef.current;
    if (!mainSeries) return;

    // Filter out invalid candles (null/NaN/undefined values crash lightweight-charts)
    const validCandlesRaw = candles.filter(c =>
      c.time != null &&
      c.open != null && !isNaN(c.open) && isFinite(c.open) &&
      c.high != null && !isNaN(c.high) && isFinite(c.high) &&
      c.low != null && !isNaN(c.low) && isFinite(c.low) &&
      c.close != null && !isNaN(c.close) && isFinite(c.close) &&
      c.volume != null && !isNaN(c.volume) && isFinite(c.volume)
    );

    // Deduplicate by time (keep last occurrence) and sort ascending
    // lightweight-charts crashes on duplicate timestamps
    const candleMap = new Map<number, CandleData>();
    for (const c of validCandlesRaw) {
      candleMap.set(c.time, c);
    }
    const validCandles = Array.from(candleMap.values()).sort((a, b) => a.time - b.time);

    if (validCandles.length === 0) {
      mainSeries.setData([]);
      volumeSeriesRef.current.setData([]);
      if (ma7Ref.current) ma7Ref.current.setData([]);
      if (ma25Ref.current) ma25Ref.current.setData([]);
      if (ma99Ref.current) ma99Ref.current.setData([]);
      if (volMaRef.current) volMaRef.current.setData([]);
      lastCandleCountRef.current = 0;
      hasInitialFitRef.current = false;
      return;
    }

    try {
      // Main series data
      if (chartType === 'candle') {
        const data: CandlestickData[] = validCandles.map(c => ({
          time: c.time as Time,
          open: Number(c.open) || 0,
          high: Number(c.high) || 0,
          low: Number(c.low) || 0,
          close: Number(c.close) || 0,
        }));
        (mainSeries as ISeriesApi<'Candlestick'>).setData(data);
      } else {
        const data: LineData[] = validCandles.map(c => ({
          time: c.time as Time,
          value: Number(c.close) || 0,
        }));
        (mainSeries as ISeriesApi<'Line'>).setData(data);
      }

      // Volume data
      const volumeData: HistogramData[] = validCandles.map(c => ({
        time: c.time as Time,
        value: Number(c.volume) || 0,
        color: c.close >= c.open ? 'rgba(14, 203, 129, 0.2)' : 'rgba(246, 70, 93, 0.2)',
      }));
      volumeSeriesRef.current.setData(volumeData);

      // MA data
      const ma7Data = calcMA(validCandles, 7);
      const ma25Data = calcMA(validCandles, 25);
      const ma99Data = calcMA(validCandles, 99);
      maDataRef.current = { ma7: ma7Data, ma25: ma25Data, ma99: ma99Data };

      if (ma7Ref.current) ma7Ref.current.setData(ma7Data.filter(Boolean) as LineData[]);
      if (ma25Ref.current) ma25Ref.current.setData(ma25Data.filter(Boolean) as LineData[]);
      if (ma99Ref.current) ma99Ref.current.setData(ma99Data.filter(Boolean) as LineData[]);

      // Volume MA
      const volMaData = calcVolumeMA(validCandles, 20);
      if (volMaRef.current) volMaRef.current.setData(volMaData.filter(Boolean) as LineData[]);

      // Current price line
      const lastCandle = validCandles[validCandles.length - 1];
      if (lastCandle && chartType === 'candle' && candleSeriesRef.current) {
        if (priceLineRef.current) {
          candleSeriesRef.current.removePriceLine(priceLineRef.current);
        }
        const isUp = lastCandle.close >= lastCandle.open;
        priceLineRef.current = candleSeriesRef.current.createPriceLine({
          price: lastCandle.close,
          color: isUp ? UP_COLOR : DOWN_COLOR,
          lineWidth: 1,
          lineStyle: LineStyle.Dashed,
          axisLabelVisible: true,
          title: '',
        });
      }
    } catch (err) {
      console.warn('Chart setData error:', err);
    }

    lastCandleCountRef.current = validCandles.length;

    if (!hasInitialFitRef.current) {
      chart.timeScale().fitContent();
      hasInitialFitRef.current = true;
    }
  }, [candles, chartType]);

  // ── Update current candle in real-time (throttled) ──
  useEffect(() => {
    if (!currentCandle) return;

    // Validate currentCandle to avoid crashes
    if (!isValidCandle(currentCandle)) {
      return;
    }

    // Store pending update
    pendingUpdateRef.current = currentCandle;

    const applyUpdate = (candle: CandleData) => {
      try {
        // Update main series
        if (chartType === 'candle' && candleSeriesRef.current) {
          candleSeriesRef.current.update({
            time: candle.time as Time,
            open: Number(candle.open) || 0,
            high: Number(candle.high) || 0,
            low: Number(candle.low) || 0,
            close: Number(candle.close) || 0,
          });
        } else if (chartType === 'line' && lineSeriesRef.current) {
          lineSeriesRef.current.update({
            time: candle.time as Time,
            value: Number(candle.close) || 0,
          });
        } else if (chartType === 'area' && areaSeriesRef.current) {
          areaSeriesRef.current.update({
            time: candle.time as Time,
            value: Number(candle.close) || 0,
          });
        }

        // Update volume
        if (volumeSeriesRef.current) {
          volumeSeriesRef.current.update({
            time: candle.time as Time,
            value: Number(candle.volume) || 0,
            color: candle.close >= candle.open
              ? 'rgba(14, 203, 129, 0.2)'
              : 'rgba(246, 70, 93, 0.2)',
          });
        }

        // Update price line
        if (priceLineRef.current && candleSeriesRef.current) {
          const isUp = candle.close >= candle.open;
          priceLineRef.current.applyOptions({
            price: Number(candle.close) || 0,
            color: isUp ? UP_COLOR : DOWN_COLOR,
          });
        }
      } catch (err) {
        console.warn('Chart update error:', err);
      }

      lastUpdateRef.current = Date.now();
    };

    const now = Date.now();
    const timeSinceLastUpdate = now - lastUpdateRef.current;

    if (timeSinceLastUpdate >= UPDATE_THROTTLE_MS) {
      // Enough time has passed, update immediately
      applyUpdate(currentCandle);
    } else {
      // Schedule update for later (if not already scheduled)
      if (!updateTimeoutRef.current) {
        updateTimeoutRef.current = setTimeout(() => {
          updateTimeoutRef.current = null;
          if (pendingUpdateRef.current && isValidCandle(pendingUpdateRef.current)) {
            applyUpdate(pendingUpdateRef.current);
          }
        }, UPDATE_THROTTLE_MS - timeSinceLastUpdate);
      }
    }
  }, [currentCandle, chartType]);

  // Cleanup timeout on unmount
  useEffect(() => {
    return () => {
      if (updateTimeoutRef.current) {
        clearTimeout(updateTimeoutRef.current);
      }
    };
  }, []);

  // ── Reset fit on interval change ──
  useEffect(() => {
    hasInitialFitRef.current = false;
    lastCandleCountRef.current = 0;
  }, [activeInterval]);

  const handleIntervalChange = useCallback((newInterval: Interval) => {
    onIntervalChange(newInterval);
  }, [onIntervalChange]);

  const handleChartTypeChange = useCallback((type: ChartType) => {
    setChartType(type);
  }, []);

  // ── Render ──
  return (
    <div className="chart-component">
      {/* Toolbar */}
      <div className="chart-toolbar">
        <div className="chart-toolbar-left">
          <div className="chart-title">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M3 3v18h18"/>
              <path d="M7 14l4-4 4 4 5-5"/>
            </svg>
            <span>{symbol}</span>
          </div>
          <div className="chart-type-selector">
            <button
              className={`chart-type-btn ${chartType === 'candle' ? 'active' : ''}`}
              onClick={() => handleChartTypeChange('candle')}
              title="Candlestick"
            >
              {/* Candlestick icon */}
              <svg viewBox="0 0 16 16" fill="currentColor">
                <rect x="3" y="2" width="2" height="12" rx="0.5" opacity="0.7"/>
                <rect x="7" y="4" width="2" height="8" rx="0.5" opacity="0.7"/>
                <rect x="11" y="1" width="2" height="10" rx="0.5" opacity="0.7"/>
              </svg>
            </button>
            <button
              className={`chart-type-btn ${chartType === 'line' ? 'active' : ''}`}
              onClick={() => handleChartTypeChange('line')}
              title="Line"
            >
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path d="M1 12l4-5 4 3 6-8"/>
              </svg>
            </button>
            <button
              className={`chart-type-btn ${chartType === 'area' ? 'active' : ''}`}
              onClick={() => handleChartTypeChange('area')}
              title="Area"
            >
              <svg viewBox="0 0 16 16" fill="currentColor" opacity="0.6">
                <path d="M1 14V12l4-5 4 3 6-8v12H1z"/>
              </svg>
            </button>
          </div>
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

      {/* Chart area wrapper */}
      <div className="chart-area">
        {/* OHLCV Legend Overlay */}
        {displayOhlcv && (
          <div className="chart-ohlcv-legend">
            <div className="ohlcv-row">
              <span className="ohlcv-label">O</span>
              <span className={displayOhlcv.isUp ? 'ohlcv-up' : 'ohlcv-down'}>
                {formatPrice(displayOhlcv.open)}
              </span>
              <span className="ohlcv-label">H</span>
              <span className={displayOhlcv.isUp ? 'ohlcv-up' : 'ohlcv-down'}>
                {formatPrice(displayOhlcv.high)}
              </span>
              <span className="ohlcv-label">L</span>
              <span className={displayOhlcv.isUp ? 'ohlcv-up' : 'ohlcv-down'}>
                {formatPrice(displayOhlcv.low)}
              </span>
              <span className="ohlcv-label">C</span>
              <span className={displayOhlcv.isUp ? 'ohlcv-up' : 'ohlcv-down'}>
                {formatPrice(displayOhlcv.close)}
              </span>
              <span className={displayOhlcv.isUp ? 'ohlcv-change-up' : 'ohlcv-change-down'}>
                {displayOhlcv.change >= 0 ? '+' : ''}{formatPrice(displayOhlcv.change)}
                ({(displayOhlcv.changePct ?? 0) >= 0 ? '+' : ''}{(displayOhlcv.changePct ?? 0).toFixed(2)}%)
              </span>
            </div>
            <div className="ohlcv-row ohlcv-vol-row">
              <span className="ohlcv-label">Vol</span>
              <span className="ohlcv-vol">{formatVolume(displayOhlcv.volume)}</span>
            </div>
          </div>
        )}

        {/* MA Legend Overlay */}
        <div className="chart-ma-legend">
          {displayMa.ma7 !== null && (
            <span className="ma-label" style={{ color: MA_COLORS.ma7 }}>
              MA(7): {formatPrice(displayMa.ma7)}
            </span>
          )}
          {displayMa.ma25 !== null && (
            <span className="ma-label" style={{ color: MA_COLORS.ma25 }}>
              MA(25): {formatPrice(displayMa.ma25)}
            </span>
          )}
          {displayMa.ma99 !== null && (
            <span className="ma-label" style={{ color: MA_COLORS.ma99 }}>
              MA(99): {formatPrice(displayMa.ma99)}
            </span>
          )}
        </div>

        {/* Chart canvas */}
        <div className="chart-container" ref={chartContainerRef} />

        {/* Empty state */}
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
    </div>
  );
}
