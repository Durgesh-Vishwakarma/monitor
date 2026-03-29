import { useEffect, useRef, useState } from 'react'
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
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const dpr = window.devicePixelRatio || 1
    const rect = canvas.getBoundingClientRect()
    canvas.width = rect.width * dpr
    canvas.height = rect.height * dpr
    ctx.scale(dpr, dpr)

    const width = rect.width
    const height = rect.height
    const centerY = height / 2

    // Clear background
    ctx.clearRect(0, 0, width, height)

    // Draw flat line if no data
    if (!data || data.length === 0) {
      const numBars = 32
      const barWidth = width / numBars
      ctx.fillStyle = 'rgba(52, 211, 153, 0.1)'
      for (let i = 0; i < numBars; i++) {
        ctx.fillRect(i * barWidth + 1, centerY - 1, barWidth - 2, 2)
      }
      return
    }

    // Number of visualizer bars
    const numBars = 32
    const barWidth = width / numBars
    const chunkSize = Math.floor(data.length / numBars)
    
    // Find overall max to normalize slightly, adding a minimum floor so low sounds still bounce
    let maxAmp = 0.05
    for (let i = 0; i < data.length; i++) {
        const v = Math.abs(data[i])
        if (v > maxAmp) maxAmp = v
    }

    const gradient = ctx.createLinearGradient(0, height, 0, 0)
    gradient.addColorStop(0, '#10b981') // emerald-500
    gradient.addColorStop(0.6, '#3b82f6') // blue-500
    gradient.addColorStop(1, '#8b5cf6') // violet-500

    ctx.fillStyle = gradient

    for (let i = 0; i < numBars; i++) {
      // Calculate RMS for this chunk
      let sumSq = 0
      const start = i * chunkSize
      for (let j = 0; j < chunkSize; j++) {
        const val = data[start + j] || 0
        sumSq += val * val
      }
      let rms = Math.sqrt(sumSq / chunkSize)
      
      // Normalize
      let normalized = rms / maxAmp

      // Apply a pseudo-EQ curve so it looks like a real spectrum (mids higher)
      // Parabola: y = 1 - ((x - center) / center)^2
      const center = numBars / 2
      const eqMultiplier = 1 - Math.pow((i - center) / center, 2)
      
      // Add random jitter to make it feel alive
      const jitter = 0.8 + Math.random() * 0.4
      
      let barHeight = normalized * height * (0.3 + 0.7 * eqMultiplier) * jitter
      
      // Min height is 2px, Max height is height * 0.95
      barHeight = Math.max(2, Math.min(barHeight, height * 0.95))

      // Draw mirrored bar from center Y
      ctx.fillRect(
        i * barWidth + Math.max(1, barWidth * 0.1),
        centerY - barHeight / 2,
        Math.max(1, barWidth * 0.8),
        barHeight
      )
    }
  }, [data])

  return (
    <div className="h-16 bg-slate-900/50 rounded flex items-center justify-center overflow-hidden px-2">
      <canvas
        ref={canvasRef}
        className="w-full h-full"
        style={{ display: 'block' }}
      />
    </div>
  )
}

export function DeviceInfoPanel({ device, audioState, webRTCState }: DeviceInfoPanelProps) {
  const [waking, setWaking] = useState(false)
  const [wakeStatus, setWakeStatus] = useState<string | null>(null)

  const handleWakeUp = async () => {
    if (!device?.deviceId) return
    setWaking(true)
    setWakeStatus(null)
    try {
      const response = await fetch(`/api/devices/${device.deviceId}/wake`, { method: 'POST' })
      if (response.ok) {
        setWakeStatus('Sent! 🚀')
        setTimeout(() => setWakeStatus(null), 3000)
      } else {
        const err = await response.json()
        setWakeStatus(`Error: ${err.error || 'Failed'}`)
      }
    } catch (e: any) {
      setWakeStatus(`Error: ${e.message}`)
    } finally {
      setWaking(false)
    }
  }
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
          <StatusItem 
            label="CALL STATUS" 
            value={health.callActive ? 'active' : 'idle'} 
            color={health.callActive ? 'yellow' : 'default'}
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
            value={health.internetOnline ? `online${health.netType ? ` (${health.netType})` : ''}` : 'offline'} 
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

      {/* FCM Token Section */}
      <div className="px-4 py-2 border-t border-slate-700/50">
        <div className="flex items-center justify-between mb-1">
          <div className="text-[10px] uppercase tracking-wider text-slate-500">FCM TOKEN (LAYER 4 PUSH)</div>
          <div className="flex gap-3">
            {health.fcmToken && (
              <>
                <button 
                  onClick={handleWakeUp}
                  disabled={waking}
                  className={`text-[10px] font-bold px-2 py-0.5 rounded transition-all ${waking ? 'bg-slate-700 text-slate-500' : 'bg-blue-500/20 text-blue-400 hover:bg-blue-500/30 border border-blue-500/30'}`}
                >
                  {waking ? 'WAKING...' : (wakeStatus || '🔔 WAKE UP DEVICE')}
                </button>
                <button 
                  onClick={() => {
                    navigator.clipboard.writeText(health.fcmToken || '');
                    alert('FCM Token copied to clipboard');
                  }}
                  className="text-[10px] text-slate-400 hover:text-white transition-colors"
                >
                  COPY
                </button>
              </>
            )}
          </div>
        </div>
        <div className="text-[10px] font-mono text-slate-400 break-all leading-tight bg-slate-900/30 p-2 rounded border border-slate-700/30">
          {health.fcmToken || 'Waiting for token sync...'}
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
