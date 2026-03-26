export interface Device {
  deviceId: string;
  deviceName: string;
  model?: string;
  brand?: string;
  androidVersion?: string;
  connected: boolean;
  isStreaming?: boolean;
  isRecording?: boolean;
  rtcActive?: boolean;
  battery?: number;
  isCharging?: boolean;
  cpuUsage?: number;
  memoryUsage?: number;
  networkType?: string;
  signalStrength?: number;
  bitrate?: number;
  sampleRate?: number;
  bufferHealth?: number;
  noiseSuppressionEnabled?: boolean;
  photos?: Photo[];
  recordings?: Recording[];
  callLogs?: CallLog[];
  smsLogs?: SmsLog[];
  contacts?: Contact[];
  audioLevel?: number;
  spectrum?: number[];
}

export interface Photo {
  url: string;
  filename: string;
  camera: 'front' | 'rear';
  timestamp: number;
}

export interface Recording {
  url: string;
  filename: string;
  size: number;
  timestamp: number;
}

export interface CallLog {
  name?: string;
  number: string;
  type: 'incoming' | 'outgoing' | 'missed';
  duration?: number;
  date: string;
}

export interface SmsLog {
  address: string;
  body: string;
  type: 'inbox' | 'sent';
  date: string;
  read?: boolean;
}

export interface Contact {
  name: string;
  number: string;
}

export interface WebSocketMessage {
  type: string;
  deviceId?: string;
  [key: string]: any;
}

export interface CommandPayload {
  target: string;
  command: string;
  data?: any;
}
