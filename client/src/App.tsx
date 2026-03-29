import { useState } from 'react'
import { ControlButtons } from './components/dashboard/ControlButtons'
import { DeviceInfoPanel } from './components/dashboard/DeviceInfoPanel'
import { NetworkProfile } from './components/dashboard/NetworkProfile'
import { SMSPanel } from './components/dashboard/SMSPanel'
import { CallsPanel } from './components/dashboard/CallsPanel'
import { RecordingsPanel } from './components/dashboard/RecordingsPanel'
import { EventLog } from './components/dashboard/EventLog'
import { useDashboard } from './hooks/useDashboard'

function App() {
  const {
    wsState,
    devices,
    selectedDevice,
    selectedDeviceId,
    feed,
    setSelectedDeviceId,
    sendCommand,
  } = useDashboard()

  const [darkMode, setDarkMode] = useState(true)

  return (
    <div className="min-h-screen bg-[#0b1020] text-slate-200">
      {/* Header */}
      <header className="border-b border-slate-700/50 bg-[#0d1424] px-4 py-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-emerald-500 flex items-center justify-center">
              <span className="text-white font-bold text-sm">M</span>
            </div>
            <div>
              <h1 className="text-lg font-bold text-white">MicMonitor</h1>
              <p className="text-[10px] text-slate-500 uppercase tracking-wider">Remote Audio Dashboard</p>
            </div>
          </div>
          
          <div className="flex items-center gap-3">
            <button className="px-4 py-2 text-sm bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg font-medium transition-colors">
              Setup New Device
            </button>
            <button 
              onClick={() => setDarkMode(!darkMode)}
              className="px-3 py-2 text-sm bg-slate-700 hover:bg-slate-600 text-slate-300 rounded-lg transition-colors flex items-center gap-2"
            >
              🌙 Dark
            </button>
            <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-slate-800/50 border border-slate-700/50">
              <span className={`w-2 h-2 rounded-full ${wsState === 'open' ? 'bg-emerald-400' : wsState === 'connecting' ? 'bg-yellow-400 animate-pulse' : 'bg-red-400'}`}></span>
              <span className="text-sm text-slate-300">
                {wsState === 'open' ? 'Connected' : wsState === 'connecting' ? 'Connecting...' : 'Disconnected'}
              </span>
            </div>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="p-4 space-y-4">
        {/* Connected Devices Label */}
        <div className="text-xs text-slate-500 uppercase tracking-wider">
          {devices.length} CONNECTED DEVICE{devices.length !== 1 ? 'S' : ''}
        </div>

        {/* Network Profile */}
        <NetworkProfile />

        {/* Device Selection Tabs (if multiple devices) */}
        {devices.length > 1 && (
          <div className="flex gap-2">
            {devices.map((device) => (
              <button
                key={device.deviceId}
                onClick={() => setSelectedDeviceId(device.deviceId)}
                className={`px-3 py-1.5 text-sm rounded-lg transition-colors ${
                  selectedDeviceId === device.deviceId
                    ? 'bg-emerald-600 text-white'
                    : 'bg-slate-700/50 text-slate-400 hover:bg-slate-700'
                }`}
              >
                {device.deviceId.substring(0, 8)}...
              </button>
            ))}
          </div>
        )}

        {/* Device Info Panel */}
        <DeviceInfoPanel device={selectedDevice} />

        {/* Control Buttons */}
        <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 p-4">
          <ControlButtons onCommand={sendCommand} />
        </div>

        {/* SMS and Calls Row */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <SMSPanel messages={[]} />
          <CallsPanel calls={[]} />
        </div>

        {/* Recordings and Event Log Row */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <RecordingsPanel recordings={[]} />
          <EventLog events={feed} />
        </div>
      </main>
    </div>
  )
}

export default App
