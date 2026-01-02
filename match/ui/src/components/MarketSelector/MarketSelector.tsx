import type { Market } from '../../types/market';
import './MarketSelector.css';

interface MarketSelectorProps {
  markets: Market[];
  selectedMarket: Market;
  onSelectMarket: (market: Market) => void;
}

export function MarketSelector({ markets, selectedMarket, onSelectMarket }: MarketSelectorProps) {
  return (
    <div className="market-selector">
      <div className="market-tabs">
        {markets.map(market => (
          <button
            key={market.id}
            className={`market-tab ${selectedMarket.id === market.id ? 'active' : ''}`}
            onClick={() => onSelectMarket(market)}
          >
            <span className="market-symbol">{market.symbol}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
