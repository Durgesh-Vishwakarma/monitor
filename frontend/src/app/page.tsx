'use client';

import { useEffect } from 'react';
import { Header } from '@/components/Header';
import { ConnectionStatus } from '@/components/ConnectionStatus';
import { DevicesContainer } from '@/components/DevicesContainer';
import { RecordingsPanel } from '@/components/RecordingsPanel';
import { QRModal } from '@/components/QRModal';
import { useWebSocket } from '@/hooks/useWebSocket';
import { useDevices } from '@/hooks/useDevices';

export default function Dashboard() {
  const { connected, socket, sendCommand } = useWebSocket();
  const { devices, addDevice, updateDevice, removeDevice } = useDevices();

  useEffect(() => {
    if (!socket) return;

    socket.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        handleMessage(msg);
      } catch (e) {
        console.error('Failed to parse message:', e);
      }
    };

    function handleMessage(msg: any) {
      switch (msg.type) {
        case 'device_list':
          msg.devices?.forEach((d: any) => addDevice(d));
          break;
        case 'device_connected':
          addDevice(msg);
          break;
        case 'device_disconnected':
          removeDevice(msg.deviceId);
          break;
        case 'audio_data':
        case 'status_update':
        case 'photo_saved':
        case 'spectrum':
          updateDevice(msg.deviceId, msg);
          break;
      }
    }
  }, [socket, addDevice, updateDevice, removeDevice]);

  return (
    <div className="min-h-screen p-6">
      <Header />
      
      <div className="flex items-center justify-between gap-3 flex-wrap mb-4">
        <ConnectionStatus connected={connected} />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div className="lg:col-span-2">
          <div className="section-label">Connected Devices</div>
          <DevicesContainer 
            devices={devices} 
            sendCommand={sendCommand}
            connected={connected}
          />
        </div>
        
        <div className="space-y-4">
          <RecordingsPanel />
        </div>
      </div>

      <QRModal />
    </div>
  );
}
