
import type { Device, Screenshot } from '../../types/dashboard'

interface Props {
  devices: Device[]
  selectedDeviceId: string
  setSelectedDeviceId: (id: string) => void
  sendCommand: (cmd: string, extra?: Record<string, unknown>) => void
  screenshots: Screenshot[]
}

export function DeviceFleetList({ devices, selectedDeviceId, setSelectedDeviceId, sendCommand, screenshots }: Props) {
  if (devices.length === 0) return null

  return (
    <div className="space-y-4 mb-6">
      <div className="text-[10px] uppercase tracking-widest font-bold text-indigo-400 mb-3 flex items-center gap-2">
        <span className="flex-1 h-px" style={{ background: 'linear-gradient(90deg, rgba(99,102,241,0.4), transparent)' }} />
        Device Fleet
        <span className="flex-1 h-px" style={{ background: 'linear-gradient(90deg, transparent, rgba(99,102,241,0.4))' }} />
      </div>

      <div className="flex flex-col gap-3">
        {devices.map((device) => {
          const isSelected = selectedDeviceId === device.deviceId
          const health = device.health || {}
          
          // Get the most recent screenshot for this device
          const deviceScreenshots = screenshots.filter(s => {
            const match = s.filename.match(/^screenshot_([a-z0-9_-]+)_/i)
            return match && match[1] === device.deviceId
          })
          const latestScreenshot = deviceScreenshots[0]

          return (
            <div
              key={device.deviceId}
              onClick={() => setSelectedDeviceId(device.deviceId)}
              className="relative p-4 rounded-xl transition-all duration-300 cursor-pointer overflow-hidden flex flex-col sm:flex-row sm:items-center justify-between gap-4"
              style={{
                background: isSelected 
                  ? 'linear-gradient(135deg, rgba(99,102,241,0.15), rgba(139,92,246,0.15))' 
                  : 'rgba(15,20,40,0.6)',
                border: `1px solid ${isSelected ? 'rgba(99,102,241,0.5)' : 'rgba(255,255,255,0.07)'}`,
                boxShadow: isSelected ? '0 0 20px rgba(99,102,241,0.1)' : '0 4px 12px rgba(0,0,0,0.2)'
              }}
            >
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 rounded-full flex items-center justify-center text-xl bg-slate-800 border border-slate-700">
                  📱
                </div>
                <div>
                  <div className="font-semibold text-slate-200">
                    {device.model || 'Unknown Device'}
                  </div>
                  <div className="text-xs text-slate-500 font-mono mt-0.5">
                    ID: {device.deviceId}
                  </div>
                  <div className="flex items-center gap-3 mt-2">
                    <StatusDot active={health.wsConnected !== false} label={health.wsConnected === false ? 'Offline' : 'Online'} />
                    {health.batteryPct != null && (
                      <span className="text-[10px] text-slate-400 font-medium">🔋 {health.batteryPct}%</span>
                    )}
                    {(health.lowNetwork || health.connQuality) && (
                      <span className="text-[10px] text-slate-400 font-medium">📶 {health.lowNetwork ? 'LOW BT' : (health.connQuality?.toUpperCase() || 'OK')}</span>
                    )}
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-4" onClick={(e) => e.stopPropagation()}>
                {latestScreenshot && (
                  <div className="relative group cursor-pointer" title="View latest screenshot">
                    <a href={latestScreenshot.url} target="_blank" rel="noreferrer">
                      <img 
                        src={latestScreenshot.url} 
                        alt="Latest Screenshot" 
                        className="w-24 h-16 object-cover rounded-lg border border-slate-600 group-hover:border-indigo-400 transition-colors"
                      />
                      <div className="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center rounded-lg backdrop-blur-[2px]">
                        <span className="text-[10px] font-bold text-white tracking-wider">VIEW</span>
                      </div>
                    </a>
                  </div>
                )}
                
                <button
                  onClick={() => {
                    // Temporarily select to ensure command goes to right device
                    setSelectedDeviceId(device.deviceId)
                    sendCommand("take_screenshot")
                  }}
                  className="px-4 py-2.5 bg-indigo-500/10 hover:bg-indigo-500/20 text-indigo-400 rounded-lg text-sm font-semibold transition-colors flex items-center gap-2 border border-indigo-500/20"
                >
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                  </svg>
                  Screenshot
                </button>
              </div>
              
              {isSelected && (
                <div className="absolute left-0 top-0 bottom-0 w-1 rounded-l-xl bg-gradient-to-b from-indigo-500 to-purple-500" />
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function StatusDot({ active, label }: { active: boolean, label: string }) {
  return (
    <div className="flex items-center gap-1.5">
      <span className={`w-2 h-2 rounded-full ${active ? 'bg-emerald-400 animate-pulse' : 'bg-red-400'}`} />
      <span className="text-[10px] text-slate-400 uppercase tracking-widest">{label}</span>
    </div>
  )
}
