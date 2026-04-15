import type { Device } from '../../types/dashboard'

interface Props {
  devices: Device[]
  selectedDeviceId: string
  setSelectedDeviceId: (id: string) => void
}

export function DeviceFleetList({ devices, selectedDeviceId, setSelectedDeviceId }: Props) {
  if (devices.length === 0) return null

  return (
    <div className="space-y-4 mb-6">
      <div className="text-[10px] uppercase tracking-widest font-bold text-indigo-400 mb-3 flex items-center gap-2">
        <span className="flex-1 h-px" style={{ background: 'linear-gradient(90deg, rgba(34,211,238,0.5), transparent)' }} />
        Device Fleet
        <span className="flex-1 h-px" style={{ background: 'linear-gradient(90deg, transparent, rgba(34,211,238,0.5))' }} />
      </div>

      <div className="flex flex-col gap-3">
        {devices.map((device) => {
          const isSelected = selectedDeviceId === device.deviceId
          const health = device.health || {}

          return (
            <div
              key={device.deviceId}
              onClick={() => setSelectedDeviceId(device.deviceId)}
              className="relative p-4 rounded-xl transition-all duration-300 cursor-pointer overflow-hidden flex flex-col sm:flex-row sm:items-center justify-between gap-4"
              style={{
                background: isSelected 
                  ? 'linear-gradient(140deg, rgba(14,165,233,0.2), rgba(34,197,94,0.14))' 
                  : 'linear-gradient(165deg, rgba(15,23,42,0.7), rgba(10,16,29,0.62))',
                border: `1px solid ${isSelected ? 'rgba(34,211,238,0.5)' : 'rgba(148,163,184,0.16)'}`,
                boxShadow: isSelected ? '0 0 22px rgba(34,211,238,0.18)' : '0 8px 16px rgba(0,0,0,0.24)'
              }}
            >
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 rounded-full flex items-center justify-center text-xl" style={{ background: 'rgba(15,23,42,0.75)', border: '1px solid rgba(71,85,105,0.55)' }}>
                  📱
                </div>
                <div>
                  <div className="font-semibold text-slate-100">
                    {device.model || 'Unknown Device'}
                  </div>
                  <div className="text-xs text-slate-400 font-mono mt-0.5">
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

              {isSelected && (
                <div className="absolute left-0 top-0 bottom-0 w-1 rounded-l-xl bg-gradient-to-b from-cyan-400 to-emerald-400" />
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
