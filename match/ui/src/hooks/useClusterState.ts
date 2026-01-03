import { useState, useCallback } from 'react';
import type { ClusterState, ClusterStatusMessage, ClusterEventMessage, ClusterNode } from '../types/market';

const INITIAL_NODES: ClusterNode[] = [
  { id: 0, status: 'OFFLINE', healthy: false },
  { id: 1, status: 'OFFLINE', healthy: false },
  { id: 2, status: 'OFFLINE', healthy: false },
];

const INITIAL_CLUSTER_STATE: ClusterState = {
  leaderId: -1,
  leadershipTermId: -1,
  nodes: INITIAL_NODES,
  gatewayConnected: false,
  isRollingUpdate: false,
  isElecting: false,
  lastEvent: null,
  lastUpdate: 0,
};

export function useClusterState() {
  const [clusterState, setClusterState] = useState<ClusterState>(INITIAL_CLUSTER_STATE);

  const handleClusterStatus = useCallback((message: ClusterStatusMessage) => {
    setClusterState(prev => {
      // Detect election state: no leader or nodes in ELECTION status
      const isElecting = message.leaderId < 0 ||
        message.nodes.some(n => n.status === 'ELECTION');

      return {
        ...prev,
        leaderId: message.leaderId,
        leadershipTermId: message.leadershipTermId,
        nodes: message.nodes,
        gatewayConnected: message.gatewayConnected,
        lastUpdate: message.timestamp,
        isElecting,
      };
    });
  }, []);

  const handleClusterEvent = useCallback((message: ClusterEventMessage) => {
    setClusterState(prev => {
      let isRollingUpdate = prev.isRollingUpdate;
      let isElecting = prev.isElecting;

      // Track rolling update start/complete
      if (message.event === 'ROLLING_UPDATE_START') {
        isRollingUpdate = true;
      } else if (message.event === 'ROLLING_UPDATE_COMPLETE') {
        isRollingUpdate = false;
      }

      // Track election state
      if (message.event === 'LEADER_CHANGE') {
        isElecting = false; // Election completed
      }

      return {
        ...prev,
        lastEvent: message,
        isRollingUpdate,
        isElecting,
      };
    });
  }, []);

  const resetClusterState = useCallback(() => {
    setClusterState(INITIAL_CLUSTER_STATE);
  }, []);

  return {
    clusterState,
    handleClusterStatus,
    handleClusterEvent,
    resetClusterState,
  };
}
