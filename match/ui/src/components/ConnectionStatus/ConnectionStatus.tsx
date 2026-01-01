import type { ConnectionStatus as ConnectionStatusType } from '../../types/market';
import './ConnectionStatus.css';

interface ConnectionStatusProps {
  status: ConnectionStatusType;
  onReconnect: () => void;
}

export function ConnectionStatus({ status, onReconnect }: ConnectionStatusProps) {
  const statusText: Record<ConnectionStatusType, string> = {
    connecting: 'Connecting...',
    connected: 'Connected',
    disconnected: 'Disconnected',
    error: 'Connection Error',
  };

  return (
    <div className={`connection-status ${status}`}>
      <span className="status-indicator" />
      <span className="status-text">{statusText[status]}</span>
      {(status === 'disconnected' || status === 'error') && (
        <button onClick={onReconnect} className="reconnect-btn">
          Reconnect
        </button>
      )}
    </div>
  );
}
