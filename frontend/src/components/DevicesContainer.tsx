import type { Device } from '@/types';
import { DeviceCard } from './DeviceCard';

interface DevicesContainerProps {
  devices: Device[];
  sendCommand: (deviceId: string, command: string, data?: any) => boolean;
  connected: boolean;
}

export function DevicesContainer({ devices, sendCommand, connected }: DevicesContainerProps) {
  const connectedDevices = devices.filter(d => d.connected);
  
  if (connectedDevices.length === 0) {
    return (
      <div className="bg-surface border border-dashed border-border2 rounded-2xl p-14 text-center text-text-dim text-sm leading-loose">
        <span className="text-4xl block mb-3.5 opacity-70">📱</span>
        <p>No devices connected</p>
        <p className="text-text-dim/70 text-xs mt-1">
          Waiting for devices to connect...
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {connectedDevices.map((device) => (
        <DeviceCard
          key={device.deviceId}
          device={device}
          sendCommand={sendCommand}
          wsConnected={connected}
        />
      ))}
    </div>
  );
}
