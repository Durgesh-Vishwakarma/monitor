export type DeviceHealth = {
  wsConnected?: boolean
  micCapturing?: boolean
  batteryPct?: number | null
  internetOnline?: boolean
  callActive?: boolean
  reason?: string
  webrtcConnected?: boolean
  bitrate?: number
  rtpPkts?: number
  connQuality?: 'excellent' | 'good' | 'fair' | 'poor'
  audioCodec?: string
  micInLevel?: number
  quality?: string
  lowNetwork?: boolean
  lastAudio?: string
  rnnoise?: boolean
  photoAi?: boolean
  nightMode?: boolean
  gain?: number
  voiceMode?: string
  streamMode?: string
  vbrMode?: string
}

export type Device = {
  deviceId: string
  model?: string
  sdk?: number
  connectedAt?: string
  appVersionName?: string
  appVersionCode?: number
  health?: DeviceHealth
}

export type SMS = {
  id: string
  sender: string
  body: string
  timestamp: string
  read: boolean
}

export type Call = {
  id: string
  number: string
  type: 'incoming' | 'outgoing' | 'missed'
  duration?: number
  timestamp: string
}

export type Recording = {
  id: string
  filename: string
  duration: number
  size: number
  timestamp: string
}

export type HealthResponse = {
  status: string
  devices: number
  ts: number
}

export type WsState = 'connecting' | 'open' | 'closed'
