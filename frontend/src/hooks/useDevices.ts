'use client';

import { useState, useCallback } from 'react';
import type { Device } from '@/types';

export function useDevices() {
  const [devices, setDevices] = useState<Map<string, Device>>(new Map());

  const addDevice = useCallback((deviceData: Partial<Device> & { deviceId: string }) => {
    setDevices((prev) => {
      const updated = new Map(prev);
      const existing = updated.get(deviceData.deviceId);
      
      updated.set(deviceData.deviceId, {
        ...existing,
        ...deviceData,
        connected: true,
      } as Device);
      
      return updated;
    });
  }, []);

  const updateDevice = useCallback((deviceId: string, data: Partial<Device>) => {
    setDevices((prev) => {
      const updated = new Map(prev);
      const existing = updated.get(deviceId);
      
      if (existing) {
        updated.set(deviceId, {
          ...existing,
          ...data,
        });
      }
      
      return updated;
    });
  }, []);

  const removeDevice = useCallback((deviceId: string) => {
    setDevices((prev) => {
      const updated = new Map(prev);
      const existing = updated.get(deviceId);
      
      if (existing) {
        // Mark as disconnected instead of removing
        updated.set(deviceId, {
          ...existing,
          connected: false,
        });
      }
      
      return updated;
    });
  }, []);

  const getDevice = useCallback((deviceId: string) => {
    return devices.get(deviceId);
  }, [devices]);

  return {
    devices: Array.from(devices.values()),
    devicesMap: devices,
    addDevice,
    updateDevice,
    removeDevice,
    getDevice,
  };
}
