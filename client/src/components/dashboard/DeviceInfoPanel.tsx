import type { Device } from '../../types/dashboard'

type DeviceInfoPanelProps = {
  device: Device | null
}

type StatusItemProps = {
  label: string
  value: string | number | undefined | null
  color?: 'green' | 'yellow' | 'red' | 'blue' | 'default'
}

function StatusItem({ label, value, color = 'default' }: StatusItemProps) {
  const colorClasses = {
    green: 'text-emerald-400',
    yellow: 'text-yellow-400',
    red: 'text-red-400',
    blue: 'text-blue-400',
    default: 'text-slate-300',
  }

  return (
    <div className="border-b border-slate-700/50 py-2">
      <div className="text-[10px] uppercase tracking-wider text-slate-500 mb-0.5">{label}</div>
      <div className={`text-sm font-medium ${colorClasses[color]}`}>
        {value ?? '-'}
      </div>
    </div>
  )
}

export function DeviceInfoPanel({ device }: DeviceInfoPanelProps) {
  if (!device) {
    return (
      <div className="rounded-lg border border-slate-700/50 bg-slate-800/50 p-4">
        <div className="text-center text-slate-500 py-8">No device selected</div>
      </div>
    )
  }

  const health = device.health || {}
  
  return (
    <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
      {/* Device Header */}
      <div className="border-b border-slate-700/50 bg-slate-800/50 px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-2 h-2 rounded-full bg-yellow-400 animate-pulse"></div>
          <div>
            <div className="text-yellow-400 font-semibold">{device.deviceId.substring(0, 10)}...</div>
            <div className="text-xs text-slate-500">
              {device.model || 'Unknown'} • Android SDK {device.sdk || 'n/a'} • App {device.appVersionName || '?'} ({device.appVersionCode || '?'})
            </div>
          </div>
        </div>
        <div className="flex gap-2">
          <span className="px-2 py-0.5 text-[10px] rounded bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">LIVE</span>
          <span className="px-2 py-0.5 text-[10px] rounded bg-slate-600/50 text-slate-400 border border-slate-600">IDLE</span>
          <span className="px-2 py-0.5 text-[10px] rounded bg-yellow-500/20 text-yellow-400 border border-yellow-500/30">LOW NETWORK</span>
        </div>
      </div>
      
      {/* Status Grid */}
      <div className="grid grid-cols-3 gap-0">
        {/* Column 1 */}
        <div className="border-r border-slate-700/50 px-4">
          <StatusItem 
            label="WEBSOCKET" 
            value={health.wsConnected ? 'connected' : 'disconnected'} 
            color={health.wsConnected ? 'green' : 'red'} 
          />
          <StatusItem 
            label="LAST HEALTH" 
            value="just now (audio_tick)" 
          />
          <StatusItem label="RTP PKTS" value={health.rtpPkts || '-'} />
          <StatusItem 
            label="CALL STATUS" 
            value={health.callActive ? 'active' : 'idle'} 
            color={health.callActive ? 'yellow' : 'default'}
          />
          <StatusItem 
            label="AUDIO CODEC" 
            value={health.audioCodec || '-'} 
          />
        </div>
        
        {/* Column 2 */}
        <div className="border-r border-slate-700/50 px-4">
          <StatusItem 
            label="MIC CAPTURE" 
            value={health.micCapturing ? 'running' : 'stopped'} 
            color={health.micCapturing ? 'green' : 'red'}
          />
          <StatusItem 
            label="WEBRTC" 
            value={health.webrtcConnected ? 'connected' : 'idle'} 
            color={health.webrtcConnected ? 'green' : 'default'}
          />
          <StatusItem label="BITRATE" value={health.bitrate ? `${health.bitrate} kbps` : '-'} />
          <StatusItem 
            label="NETWORK" 
            value={health.internetOnline ? 'online' : 'offline'} 
            color={health.internetOnline ? 'green' : 'red'}
          />
          <StatusItem 
            label="CONN QUALITY" 
            value={health.connQuality || '-'} 
            color={health.connQuality === 'excellent' ? 'green' : health.connQuality === 'poor' ? 'red' : 'yellow'}
          />
        </div>
        
        {/* Column 3 */}
        <div className="px-4">
          <StatusItem label="LAST AUDIO" value={health.lastAudio || 'just now'} />
          <StatusItem 
            label="MIC IN LEVEL" 
            value={health.micInLevel !== undefined ? `${health.micInLevel}%` : 'ON'} 
            color="green"
          />
          <StatusItem label="QUALITY" value={health.quality || '-'} />
          <StatusItem 
            label="LOW" 
            value={health.lowNetwork ? 'YES (discharging)' : 'NO'} 
            color={health.lowNetwork ? 'yellow' : 'default'}
          />
          <StatusItem 
            label="BATTERY" 
            value={health.batteryPct !== undefined ? `${health.batteryPct}%` : '-'} 
            color={health.batteryPct && health.batteryPct < 20 ? 'red' : 'default'}
          />
        </div>
      </div>
      
      {/* Waveform placeholder */}
      <div className="border-t border-slate-700/50 px-4 py-3">
        <div className="text-[10px] uppercase tracking-wider text-slate-500 mb-2">WAVEFORM</div>
        <div className="h-12 bg-slate-900/50 rounded flex items-center justify-center overflow-hidden">
          <div className="flex items-end gap-0.5 h-8">
            {Array.from({ length: 50 }).map((_, i) => (
              <div 
                key={i} 
                className="w-1 bg-emerald-500/60 rounded-t"
                style={{ height: `${Math.random() * 100}%` }}
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
