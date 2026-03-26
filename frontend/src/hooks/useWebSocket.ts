'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import type { CommandPayload } from '@/types';

export function useWebSocket() {
  const [connected, setConnected] = useState(false);
  const socketRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  const getWebSocketUrl = useCallback(() => {
    if (typeof window === 'undefined') return '';
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    return `${protocol}//${host}`;
  }, []);

  const connect = useCallback(() => {
    const url = getWebSocketUrl();
    if (!url) return;

    try {
      const ws = new WebSocket(url);

      ws.onopen = () => {
        console.log('WebSocket connected');
        setConnected(true);
        // Request device list on connect
        ws.send(JSON.stringify({ type: 'get_devices' }));
      };

      ws.onclose = () => {
        console.log('WebSocket disconnected');
        setConnected(false);
        socketRef.current = null;
        
        // Reconnect after 3 seconds
        reconnectTimeoutRef.current = setTimeout(() => {
          console.log('Attempting to reconnect...');
          connect();
        }, 3000);
      };

      ws.onerror = (error) => {
        console.error('WebSocket error:', error);
      };

      socketRef.current = ws;
    } catch (error) {
      console.error('Failed to create WebSocket:', error);
    }
  }, [getWebSocketUrl]);

  useEffect(() => {
    connect();

    return () => {
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
      if (socketRef.current) {
        socketRef.current.close();
      }
    };
  }, [connect]);

  const sendCommand = useCallback((deviceId: string, command: string, data?: any) => {
    if (!socketRef.current || socketRef.current.readyState !== WebSocket.OPEN) {
      console.warn('WebSocket not connected');
      return false;
    }

    const payload: CommandPayload = {
      target: deviceId,
      command,
      ...(data && { data }),
    };

    socketRef.current.send(JSON.stringify(payload));
    return true;
  }, []);

  const sendRaw = useCallback((data: any) => {
    if (!socketRef.current || socketRef.current.readyState !== WebSocket.OPEN) {
      console.warn('WebSocket not connected');
      return false;
    }

    socketRef.current.send(JSON.stringify(data));
    return true;
  }, []);

  return {
    connected,
    socket: socketRef.current,
    sendCommand,
    sendRaw,
  };
}
