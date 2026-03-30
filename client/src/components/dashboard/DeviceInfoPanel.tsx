import { useEffect, useRef, useState } from 'react'
import type { Device } from '../../types/dashboard'
import type { AudioPlaybackState } from '../../hooks/useAudioPlayback'
import type { WebRTCStats } from '../../hooks/useWebRTC'

type DeviceInfoPanelProps = {
  device: Device | null
  audioState?: AudioPlaybackState
  webRTCState?: WebRTCStats
}

// ── Mini status metric card ────────────────────────────────────────────────────
function MetricCard({ label, value, color = 'default', glow = false }: {
  label: string
  value: string | number | undefined | null
  color?: 'green' | 'yellow' | 'red' | 'blue' | 'violet' | 'default'
  glow?: boolean
}) {
  const colors = {
    green:   { text: '#10b981', bg: 'rgba(16,185,129,0.08)',   border: 'rgba(16,185,129,0.2)',  glow: 'rgba(16,185,129,0.15)' },
    yellow:  { text: '#f59e0b', bg: 'rgba(245,158,11,0.08)',   border: 'rgba(245,158,11,0.2)',  glow: 'rgba(245,158,11,0.15)' },
    red:     { text: '#ef4444', bg: 'rgba(239,68,68,0.08)',    border: 'rgba(239,68,68,0.2)',   glow: 'rgba(239,68,68,0.15)' },
    blue:    { text: '#60a5fa', bg: 'rgba(96,165,250,0.08)',   border: 'rgba(96,165,250,0.2)',  glow: 'rgba(96,165,250,0.15)' },
    violet:  { text: '#a78bfa', bg: 'rgba(167,139,250,0.08)',  border: 'rgba(167,139,250,0.2)', glow: 'rgba(167,139,250,0.15)' },
    default: { text: '#94a3b8', bg: 'rgba(148,163,184,0.05)', border: 'rgba(148,163,184,0.1)', glow: 'transparent' },
  }
  const c = colors[color]
  return (
    <div className="flex flex-col gap-1 p-3 rounded-xl" style={{
      background: c.bg,
      border: `1px solid ${c.border}`,
      boxShadow: glow ? `0 0 18px ${c.glow}` : 'none',
    }}>
      <span className="text-[9px] uppercase tracking-widest font-semibold" style={{ color: '#64748b' }}>{label}</span>
      <span className="text-sm font-bold leading-none" style={{ color: c.text }}>{value ?? '—'}</span>
    </div>
  )
}

// ── Animated waveform visualizer ───────────────────────────────────────────────
function Waveform({ data, isPlaying }: { data: Float32Array | null; isPlaying: boolean }) {
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

    ctx.clearRect(0, 0, width, height)

    const numBars = 48
    const barWidth = width / numBars

    if (!data || data.length === 0) {
      // Animated idle bars
      for (let i = 0; i < numBars; i++) {
        const idle = isPlaying ? (2 + Math.random() * 6) : 2
        ctx.fillStyle = 'rgba(99,102,241,0.15)'
        ctx.beginPath()
        ctx.roundRect(i * barWidth + 1, centerY - idle / 2, barWidth - 2, idle, 2)
        ctx.fill()
      }
      return
    }

    let maxAmp = 0.05
    for (let i = 0; i < data.length; i++) {
      const v = Math.abs(data[i])
      if (v > maxAmp) maxAmp = v
    }

    const chunkSize = Math.floor(data.length / numBars)

    for (let i = 0; i < numBars; i++) {
      let sumSq = 0
      const start = i * chunkSize
      for (let j = 0; j < chunkSize; j++) {
        const val = data[start + j] || 0
        sumSq += val * val
      }
      const rms = Math.sqrt(sumSq / chunkSize)
      let normalized = rms / maxAmp
      const center = numBars / 2
      const eqMultiplier = 1 - Math.pow((i - center) / center, 2) * 0.4
      const jitter = 0.85 + Math.random() * 0.3
      let barHeight = normalized * height * 0.85 * eqMultiplier * jitter
      barHeight = Math.max(3, Math.min(barHeight, height * 0.92))

      // Gradient per bar based on intensity
      const intensity = normalized
      const hue = 145 + intensity * 100  // green → blue → violet
      const sat = 65 + intensity * 20
      const lit = 45 + intensity * 15
      ctx.fillStyle = `hsla(${hue}, ${sat}%, ${lit}%, ${0.7 + intensity * 0.3})`

      ctx.beginPath()
      ctx.roundRect(
        i * barWidth + Math.max(1, barWidth * 0.12),
        centerY - barHeight / 2,
        Math.max(1, barWidth * 0.76),
        barHeight,
        3
      )
      ctx.fill()
    }
  }, [data, isPlaying])

  return (
    <div className="relative h-20 rounded-xl overflow-hidden" style={{
      background: 'rgba(6,8,18,0.6)',
      border: '1px solid rgba(99,102,241,0.15)',
      boxShadow: 'inset 0 0 30px rgba(0,0,0,0.4)'
    }}>
      <canvas ref={canvasRef} className="w-full h-full" style={{ display: 'block' }} />
      {isPlaying && (
        <div className="absolute inset-0 pointer-events-none" style={{
          background: 'linear-gradient(90deg, rgba(6,8,18,0.6) 0%, transparent 10%, transparent 90%, rgba(6,8,18,0.6) 100%)'
        }} />
      )}
    </div>
  )
}

// ── Main panel ─────────────────────────────────────────────────────────────────
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
      <div className="rounded-2xl p-8 text-center" style={{
        background: 'rgba(15,20,40,0.6)',
        border: '1px solid rgba(255,255,255,0.07)',
        backdropFilter: 'blur(12px)'
      }}>
        <div className="text-4xl mb-3 opacity-20">📱</div>
        <div className="text-slate-500 text-sm">No device selected</div>
      </div>
    )
  }

  const health = device.health || {}
  const isStreaming = audioState?.isPlaying || false
  const webRtcConnected = webRTCState?.state === 'connected'
  const isLowNetwork = health.lowNetwork === true

  // Connection quality color
  const qualityColor = health.connQuality === 'excellent' ? 'green'
    : health.connQuality === 'good' ? 'blue'
    : health.connQuality === 'poor' ? 'red'
    : 'yellow'

  return (
    <div className="rounded-2xl overflow-hidden" style={{
      background: 'rgba(12,16,32,0.7)',
      border: '1px solid rgba(99,102,241,0.15)',
      backdropFilter: 'blur(16px)',
      boxShadow: '0 8px 40px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.05)'
    }}>
      
      {/* ── Device header ─────────────────────────────────────────────── */}
      <div className="px-5 py-4" style={{
        background: 'linear-gradient(135deg, rgba(99,102,241,0.12) 0%, rgba(139,92,246,0.08) 100%)',
        borderBottom: '1px solid rgba(99,102,241,0.15)'
      }}>
        <div className="flex items-start justify-between gap-4">
          {/* Device identity */}
          <div className="flex items-center gap-3 min-w-0">
            {/* Online indicator */}
            <div className="relative shrink-0">
              <div className="w-10 h-10 rounded-xl flex items-center justify-center text-xl" style={{
                background: health.wsConnected ? 'rgba(16,185,129,0.15)' : 'rgba(245,158,11,0.12)',
                border: `1px solid ${health.wsConnected ? 'rgba(16,185,129,0.3)' : 'rgba(245,158,11,0.25)'}`
              }}>
                📱
              </div>
              <span className={`absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full border-2 border-[#0c1020] ${health.wsConnected ? 'bg-emerald-400' : 'bg-yellow-400 animate-pulse'}`} />
            </div>
            <div className="min-w-0">
              <div className="font-mono text-sm font-bold text-white truncate">
                {device.deviceId}
              </div>
              <div className="text-xs text-slate-400 mt-0.5 truncate">
                {device.model || 'Unknown device'} &nbsp;·&nbsp; SDK {device.sdk || '?'} &nbsp;·&nbsp; v{device.appVersionName || '?'} <span className="text-slate-600">({device.appVersionCode || '?'})</span>
              </div>
            </div>
          </div>

          {/* Status badges */}
          <div className="flex flex-wrap gap-2 shrink-0">
            {isStreaming && <Badge color="green" dot pulse>LIVE</Badge>}
            {webRtcConnected && <Badge color="blue">WEBRTC</Badge>}
            {isLowNetwork && <Badge color="yellow" dot>LOW-BW</Badge>}
            {health.callActive && <Badge color="red" dot pulse>ON CALL</Badge>}
            {!isStreaming && !webRtcConnected && !health.callActive && <Badge color="gray">IDLE</Badge>}
          </div>
        </div>
      </div>

      {/* ── Metrics grid ──────────────────────────────────────────────── */}
      <div className="p-4">
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2 mb-4">
          <MetricCard label="WebSocket" value={health.wsConnected ? 'Connected' : 'Disconnected'}
            color={health.wsConnected ? 'green' : 'red'} glow={health.wsConnected} />
          <MetricCard label="Mic Capture" value={health.micCapturing ? 'Running' : 'Stopped'}
            color={health.micCapturing ? 'green' : 'red'} />
          <MetricCard label="Conn Quality" value={health.connQuality || '—'} color={qualityColor as any} />
          <MetricCard label="Network" value={health.internetOnline
            ? `Online${health.netType ? ` · ${health.netType.toUpperCase()}` : ''}` : 'Offline'}
            color={health.internetOnline ? 'green' : 'red'} />
          <MetricCard label="Voice Profile" value={health.voiceProfile
            ? health.voiceProfile.charAt(0).toUpperCase() + health.voiceProfile.slice(1) : '—'}
            color={health.voiceProfile === 'far' ? 'yellow' : health.voiceProfile === 'near' ? 'blue' : 'default'} />
          <MetricCard label="Stream Codec" value={health.streamCodec ? `${health.streamCodec.toUpperCase()} ${health.streamCodecMode || ''}` : '—'} color="violet" />
          <MetricCard label="Battery" value={health.batteryPct != null
            ? `${health.batteryPct}%${health.charging ? ' ⚡' : ''}` : '—'}
            color={health.batteryPct != null && health.batteryPct < 20 ? 'red' : health.batteryPct != null && health.batteryPct > 60 ? 'green' : 'yellow'} />
          <MetricCard label="Noise Floor" value={health.noiseDb !== undefined && health.noiseDb !== null
            ? `${health.noiseDb.toFixed(0)} dB` : '—'} color="default" />
          <MetricCard label="Audio Latency" value={audioState?.latencyMs ? `${audioState.latencyMs}ms` : '—'}
            color={audioState?.latencyMs && audioState.latencyMs > 500 ? 'yellow' : 'default'} />
          <MetricCard label="Buffer Health" value={audioState?.bufferHealth !== undefined
            ? `${Math.round(audioState.bufferHealth * 100)}%` : '—'}
            color={audioState?.bufferHealth !== undefined && audioState.bufferHealth < 0.2 ? 'red' : 'default'} />
          <MetricCard label="WebRTC" value={webRtcConnected ? 'Connected' : webRTCState?.state || 'Idle'}
            color={webRtcConnected ? 'green' : webRTCState?.state === 'connecting' ? 'yellow' : 'default'} />
          <MetricCard label="MIC Level" value={health.micInLevel !== undefined ? `${health.micInLevel}%` : 'Active'}
            color="green" glow={isStreaming} />
        </div>

        {/* ── Waveform ──────────────────────────────────────────────────── */}
        <div className="mb-4">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[10px] uppercase tracking-widest font-semibold text-indigo-400">Waveform</span>
            {isStreaming && (
              <div className="flex items-center gap-1.5">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
                <span className="text-[10px] text-emerald-400 font-medium">LIVE AUDIO</span>
              </div>
            )}
          </div>
          <Waveform data={audioState?.waveform || null} isPlaying={isStreaming} />
        </div>

        {/* ── FCM Token ─────────────────────────────────────────────────── */}
        <div className="rounded-xl p-3" style={{
          background: 'rgba(6,8,18,0.5)',
          border: '1px solid rgba(255,255,255,0.05)'
        }}>
          <div className="flex items-center justify-between mb-2">
            <span className="text-[9px] uppercase tracking-widest font-bold text-slate-500">FCM Push Token</span>
            {health.fcmToken && (
              <div className="flex gap-2">
                <button
                  onClick={handleWakeUp}
                  disabled={waking}
                  className="text-[10px] font-semibold px-3 py-1 rounded-lg transition-all"
                  style={{
                    background: waking ? 'rgba(100,116,139,0.2)' : 'rgba(99,102,241,0.2)',
                    border: `1px solid ${waking ? 'rgba(100,116,139,0.3)' : 'rgba(99,102,241,0.4)'}`,
                    color: waking ? '#64748b' : '#a5b4fc',
                    cursor: waking ? 'not-allowed' : 'pointer'
                  }}
                >
                  {waking ? '…Waking' : (wakeStatus || '🔔 Wake Device')}
                </button>
                <button
                  onClick={() => { navigator.clipboard.writeText(health.fcmToken || ''); }}
                  className="text-[10px] px-2 py-1 rounded-lg transition-all"
                  style={{
                    background: 'rgba(148,163,184,0.08)',
                    border: '1px solid rgba(148,163,184,0.15)',
                    color: '#64748b'
                  }}
                >
                  COPY
                </button>
              </div>
            )}
          </div>
          <div className="text-[10px] font-mono text-slate-500 break-all leading-relaxed">
            {health.fcmToken
              ? health.fcmToken
              : <span className="italic text-slate-600">Waiting for token sync from device…</span>}
          </div>
        </div>
      </div>
    </div>
  )
}

// ── Badge component ────────────────────────────────────────────────────────────
function Badge({ children, color, dot = false, pulse = false }: {
  children: React.ReactNode
  color: 'green' | 'blue' | 'yellow' | 'red' | 'gray' | 'violet'
  dot?: boolean
  pulse?: boolean
}) {
  const colors = {
    green:  { bg: 'rgba(16,185,129,0.15)',  border: 'rgba(16,185,129,0.35)',  text: '#34d399' },
    blue:   { bg: 'rgba(96,165,250,0.15)',  border: 'rgba(96,165,250,0.35)',  text: '#60a5fa' },
    yellow: { bg: 'rgba(245,158,11,0.15)',  border: 'rgba(245,158,11,0.35)',  text: '#fbbf24' },
    red:    { bg: 'rgba(239,68,68,0.15)',   border: 'rgba(239,68,68,0.35)',   text: '#f87171' },
    gray:   { bg: 'rgba(100,116,139,0.15)', border: 'rgba(100,116,139,0.35)', text: '#94a3b8' },
    violet: { bg: 'rgba(167,139,250,0.15)', border: 'rgba(167,139,250,0.35)', text: '#a78bfa' },
  }
  const c = colors[color]
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg text-[10px] font-bold tracking-wider" style={{
      background: c.bg, border: `1px solid ${c.border}`, color: c.text
    }}>
      {dot && <span className={`w-1.5 h-1.5 rounded-full bg-current ${pulse ? 'animate-pulse' : ''}`} />}
      {children}
    </span>
  )
}
