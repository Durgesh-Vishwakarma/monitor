import type { Device } from '../../types/dashboard'
import type { AudioPlaybackState } from '../../hooks/useAudioPlayback'
import type { WebRTCStats } from '../../hooks/useWebRTC'

type DeviceInfoPanelProps = {
  device: Device | null
  audioState?: AudioPlaybackState
  webRTCState?: WebRTCStats
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

function Waveform({ data }: { data: Float32Array | null }) {
  const bars = data ? Array.from(data) : Array.from({ length: 64 }, () => 0)
  const maxVal = Math.max(...bars.map(Math.abs), 0.01)
  
  return (
    <div className="h-12 bg-slate-900/50 rounded flex items-center justify-center overflow-hidden px-2">
      <div className="flex items-center gap-0.5 h-10 w-full">
        {bars.slice(0, 64).map((val, i) => {
          const height = Math.max(2, (Math.abs(val) / maxVal) * 100)
          return (
            <div 
              key={i} 
              className="flex-1 bg-emerald-500/70 rounded transition-all duration-75"
              style={{ height: `${height}%`, minWidth: '2px' }}
            />
          )
        })}
      </div>
    </div>
  )
}

export function DeviceInfoPanel({ device, audioState, webRTCState }: DeviceInfoPanelProps) {
  if (!device) {
    return (
      <div className="rounded-lg border border-slate-700/50 bg-slate-800/50 p-4">
        <div className="text-center text-slate-500 py-8">No device selected</div>
      </div>
    )
  }

  const health = device.health || {}
  const isStreaming = audioState?.isPlaying || false
  const webRtcConnected = webRTCState?.state === 'connected'
  
  return (
    <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
      {/* Device Header */}
      <div className="border-b border-slate-700/50 bg-slate-800/50 px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className={`w-2 h-2 rounded-full ${health.wsConnected ? 'bg-emerald-400' : 'bg-yellow-400'} ${health.wsConnected ? '' : 'animate-pulse'}`}></div>
          <div>
            <div className="text-yellow-400 font-semibold">{device.deviceId.substring(0, 10)}...</div>
            <div className="text-xs text-slate-500">
              {device.model || 'Unknown'} • Android SDK {device.sdk || 'n/a'} • App {device.appVersionName || '?'} ({device.appVersionCode || '?'})
            </div>
          </div>
        </div>
        <div className="flex gap-2">
          {isStreaming && (
            <span className="px-2 py-0.5 text-[10px] rounded bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">LIVE</span>
          )}
          {webRtcConnected && (
            <span className="px-2 py-0.5 text-[10px] rounded bg-blue-500/20 text-blue-400 border border-blue-500/30">WEBRTC</span>
          )}
          {!isStreaming && !webRtcConnected && (
            <span className="px-2 py-0.5 text-[10px] rounded bg-slate-600/50 text-slate-400 border border-slate-600">IDLE</span>
          )}
          {health.lowNetwork && (
            <span className="px-2 py-0.5 text-[10px] rounded bg-yellow-500/20 text-yellow-400 border border-yellow-500/30">LOW NETWORK</span>
          )}
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
            value={webRtcConnected ? 'connected' : (webRTCState?.state || 'idle')} 
            color={webRtcConnected ? 'green' : webRTCState?.state === 'connecting' ? 'yellow' : 'default'}
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
          <StatusItem 
            label="LAST AUDIO" 
            value={audioState?.isPlaying ? 'streaming' : 'idle'} 
            color={audioState?.isPlaying ? 'green' : 'default'}
          />
          <StatusItem 
            label="MIC IN LEVEL" 
            value={health.micInLevel !== undefined ? `${health.micInLevel}%` : 'ON'} 
            color="green"
          />
          <StatusItem 
            label="LATENCY" 
            value={audioState?.latencyMs ? `${audioState.latencyMs}ms` : '-'} 
            color={audioState?.latencyMs && audioState.latencyMs > 500 ? 'yellow' : 'default'}
          />
          <StatusItem 
            label="BUFFER" 
            value={audioState?.bufferHealth !== undefined ? `${Math.round(audioState.bufferHealth * 100)}%` : '-'} 
            color={audioState?.bufferHealth && audioState.bufferHealth < 0.2 ? 'red' : 'default'}
          />
          <StatusItem 
            label="BATTERY" 
            value={health.batteryPct !== undefined ? `${health.batteryPct}%` : '-'} 
            color={health.batteryPct && health.batteryPct < 20 ? 'red' : 'default'}
          />
        </div>
      </div>
      
      {/* Waveform */}
      <div className="border-t border-slate-700/50 px-4 py-3">
        <div className="flex items-center justify-between mb-2">
          <div className="text-[10px] uppercase tracking-wider text-slate-500">WAVEFORM</div>
          {audioState?.isPlaying && (
            <div className="flex items-center gap-1">
              <div className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></div>
              <span className="text-[10px] text-emerald-400">LIVE</span>
            </div>
          )}
        </div>
        <Waveform data={audioState?.waveform || null} />
      </div>
    </div>
  )
}
