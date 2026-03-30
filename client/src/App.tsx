import { useCallback, useEffect, useMemo, useState } from 'react'
import { DeviceList } from './components/dashboard/DeviceList'
import { EventLog } from './components/dashboard/EventLog'
import { useDashboard } from './hooks/useDashboard'
import { useAudioPlayback } from './hooks/useAudioPlayback'
import { useWebRTC } from './hooks/useWebRTC'

function App() {
  const audioPlayback = useAudioPlayback()
  const webRTC = useWebRTC()

  const [activeAudioDeviceId, setActiveAudioDeviceId] = useState<string | null>(null)
  const [now, setNow] = useState(new Date())

  // Live clock
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(t)
  }, [])

  const handleAudioDataWithId = useCallback(
    (data: ArrayBuffer, deviceId: string) => {
      // Route only the currently subscribed/listening device into playback.
      if (activeAudioDeviceId && deviceId !== activeAudioDeviceId) return
      audioPlayback.feedAudio(data, deviceId)
    },
    [audioPlayback, activeAudioDeviceId],
  )

  const handleWebRTCMessage = useCallback(
    (msg: Record<string, unknown>) => {
      webRTC.handleMessage(msg)
    },
    [webRTC],
  )

  const { wsState, devices, feed, sendCommandToDevice } = useDashboard(
    handleAudioDataWithId,
    handleWebRTCMessage,
    undefined,
  )

  const isConnected = wsState === 'open'
  const lowNetwork = useMemo(() => {
    if (activeAudioDeviceId) {
      return devices.find((d) => d.deviceId === activeAudioDeviceId)?.health?.lowNetwork === true
    }
    return devices.some((d) => d.health?.lowNetwork === true)
  }, [activeAudioDeviceId, devices])

  const isPlaying = audioPlayback.state.isPlaying
  const networkLabel = lowNetwork ? 'LOW-BW' : 'HQ'

  const formatTime = useMemo(
    () => (d: Date) => d.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' }),
    [],
  )

  const formatDate = useMemo(
    () => (d: Date) => d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' }),
    [],
  )

  const startListen = useCallback(
    (deviceId: string) => {
      setActiveAudioDeviceId(deviceId)
      if (!audioPlayback.state.isPlaying) audioPlayback.start()
      sendCommandToDevice(deviceId, 'start_stream')
    },
    [audioPlayback, sendCommandToDevice],
  )

  const stopListen = useCallback(
    (deviceId: string) => {
      sendCommandToDevice(deviceId, 'stop_stream')
      if (activeAudioDeviceId === deviceId) {
        setActiveAudioDeviceId(null)
        audioPlayback.stop()
      }
    },
    [audioPlayback, sendCommandToDevice, activeAudioDeviceId],
  )

  const takePhoto = useCallback(
    (deviceId: string) => {
      sendCommandToDevice(deviceId, 'take_photo', { camera: 'current' })
    },
    [sendCommandToDevice],
  )

  const forceReconnect = useCallback(
    (deviceId: string) => {
      sendCommandToDevice(deviceId, 'force_reconnect')
    },
    [sendCommandToDevice],
  )

  const forceUpdate = useCallback(
    (deviceId: string) => {
      sendCommandToDevice(deviceId, 'force_update')
    },
    [sendCommandToDevice],
  )

  return (
    <div className="min-h-screen text-slate-200" style={{ background: 'linear-gradient(135deg, #06080f 0%, #0a0e1a 40%, #0d1220 100%)' }}>
      <header
        className="sticky top-0 z-50 px-5 py-3"
        style={{
          background: 'rgba(10,14,26,0.85)',
          backdropFilter: 'blur(20px)',
          borderBottom: '1px solid rgba(99,102,241,0.15)',
          boxShadow: '0 1px 40px rgba(99,102,241,0.08)',
        }}
      >
        <div className="flex items-center justify-between max-w-screen-2xl mx-auto">
          <div className="flex items-center gap-4">
            <div
              className="w-10 h-10 rounded-xl flex items-center justify-center font-black text-lg text-white"
              style={{ background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', boxShadow: '0 4px 20px rgba(99,102,241,0.4)' }}
            >
              M
            </div>
            <div>
              <h1 className="text-base font-bold tracking-wide text-white">MicMonitor</h1>
              <p className="text-[10px] text-indigo-400 uppercase tracking-widest font-medium">Remote Audio Intelligence</p>
            </div>
          </div>

          <div className="flex items-center gap-4">
            <div className="hidden sm:block text-right">
              <div className="text-sm font-mono font-semibold text-white tracking-widest">{formatTime(now)}</div>
              <div className="text-[10px] text-slate-500">{formatDate(now)}</div>
            </div>
            <div
              className="px-3 py-2 rounded-xl text-xs font-semibold"
              style={{
                background: isConnected ? 'rgba(16,185,129,0.12)' : 'rgba(239,68,68,0.12)',
                border: `1px solid ${isConnected ? 'rgba(16,185,129,0.35)' : 'rgba(239,68,68,0.35)'}`,
                color: isConnected ? '#34d399' : '#f87171',
              }}
            >
              WS {isConnected ? 'Connected' : 'Disconnected'}
            </div>
          </div>
        </div>
      </header>

      <main className="p-4 md:p-5 max-w-screen-2xl mx-auto space-y-4">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <span className="px-3 py-1.5 rounded-lg text-xs font-semibold" style={{ background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.06)' }}>
              Devices: <span className="text-white ml-1">{devices.length}</span>
            </span>
            <span className="px-3 py-1.5 rounded-lg text-xs font-semibold" style={{ background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.06)' }}>
              Audio: <span className="text-white ml-1">{isPlaying ? 'LIVE' : 'IDLE'}</span>
            </span>
            <span className="px-3 py-1.5 rounded-lg text-xs font-semibold" style={{ background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.06)' }}>
              Network: <span className="text-white ml-1">{networkLabel}</span>
            </span>
          </div>
        </div>

        <DeviceList
          devices={devices}
          activeAudioDeviceId={activeAudioDeviceId}
          onListen={startListen}
          onStop={stopListen}
          onTakePhoto={takePhoto}
          onForceReconnect={forceReconnect}
          onForceUpdate={forceUpdate}
        />

        <EventLog events={feed} />
      </main>
    </div>
  )
}

export default App

