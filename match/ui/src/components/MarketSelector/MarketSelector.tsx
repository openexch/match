import type { Market } from '../../types/market';
import './MarketSelector.css';

interface MarketSelectorProps {
  markets: Market[];
  selectedMarket: Market;
  onSelectMarket: (market: Market) => void;
  isOverlay?: boolean;
  onClose?: () => void;
}

export function MarketSelector({ markets, selectedMarket, onSelectMarket, isOverlay, onClose }: MarketSelectorProps) {
  const handleSelect = (market: Market) => {
    onSelectMarket(market);
    if (onClose) onClose();
  };

  const content = (
    <div className={`market-selector ${isOverlay ? 'market-selector-in-overlay' : ''}`}>
      <div className="market-list-header">
        {isOverlay && (
          <span className="market-selector-overlay-title">Select Market</span>
        )}
        {!isOverlay && 'Markets'}
        {isOverlay && (
          <button className="market-selector-close" onClick={onClose} aria-label="Close">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        )}
      </div>
      <div className="market-list">
        {markets.map(market => (
          <button
            key={market.id}
            className={`market-list-item ${selectedMarket.id === market.id ? 'active' : ''}`}
            onClick={() => handleSelect(market)}
          >
            <div className="market-item-info">
              <span className="market-item-name">{market.name}</span>
              <span className="market-item-pair">{market.baseAsset}/{market.quoteAsset}</span>
            </div>
          </button>
        ))}
      </div>
    </div>
  );

  if (isOverlay) {
    return (
      <div className="market-selector-overlay" onClick={(e) => {
        if (e.target === e.currentTarget && onClose) onClose();
      }}>
        <div className="market-selector-overlay-content">
          {content}
        </div>
      </div>
    );
  }

  return content;
}
