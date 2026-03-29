export type DeviceHealth = {
  wsConnected?: boolean
  micCapturing?: boolean
  batteryPct?: number | null
  charging?: boolean
  internetOnline?: boolean
  callActive?: boolean
  reason?: string
  webrtcConnected?: boolean
  bitrate?: number
  rtpPkts?: number
  connQuality?: 'excellent' | 'good' | 'fair' | 'poor'
  audioCodec?: string
  streamCodec?: string
  streamCodecMode?: string
  micInLevel?: number
  quality?: string
  lowNetwork?: boolean
  lastAudio?: string
  aiMode?: boolean
  aiAuto?: boolean
  photoAi?: boolean
  photoQuality?: string
  photoNight?: string
  noiseDb?: number
  voiceProfile?: string
  streamMode?: string
  lastAudioChunkAt?: number
  lastHealthAt?: number
  appVersionName?: string
  appVersionCode?: number
}

export type Device = {
  deviceId: string
  model?: string
  sdk?: number
  connectedAt?: string
  appVersionName?: string
  appVersionCode?: number
  health?: DeviceHealth
  sms?: SMS[]
  calls?: Call[]
}

export type SMS = {
  id: string
  sender: string
  body: string
  timestamp: string
  type: 'inbox' | 'sent' | 'draft' | 'other'
  read: boolean
}

export type Call = {
  id: string
  number: string
  name?: string
  type: 'incoming' | 'outgoing' | 'missed' | 'rejected' | 'other'
  duration?: number
  timestamp: string
}

export type Recording = {
  id: string
  filename: string
  duration: number
  size: number
  timestamp: string
  url?: string
}

export type Photo = {
  id: string
  filename: string
  url: string
  camera: 'front' | 'rear'
  quality: 'fast' | 'normal' | 'hd'
  aiEnhanced: boolean
  size: number
  timestamp: string
}

export type CameraFrame = {
  deviceId: string
  camera: 'front' | 'rear'
  quality: string
  mime: string
  data: string // base64
  timestamp: number
}

export type HealthResponse = {
  status: string
  devices: number
  ts: number
}

export type WsState = 'connecting' | 'open' | 'closed'

// Device state for dynamic controls
export type DeviceCapabilities = {
  isStreaming: boolean
  isRecording: boolean
  isWebRtcActive: boolean
  isCameraLive: boolean
  aiMode: boolean
  aiAuto: boolean
  photoAi: boolean
  lowNetwork: boolean
  voiceProfile: 'near' | 'room' | 'far'
  photoQuality: 'fast' | 'normal' | 'hd'
  photoNight: 'off' | '1s' | '3s' | '5s'
  streamCodec: 'auto' | 'pcm' | 'smart'
}
