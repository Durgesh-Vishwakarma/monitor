import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { apiUrl, wsUrlForControl } from '../lib/helpers'
import type { Device, DeviceHealth, HealthResponse, WsState } from '../types/dashboard'

export function useDashboard() {
  const [wsState, setWsState] = useState<WsState>('connecting')
  const [devices, setDevices] = useState<Device[]>([])
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>('')
  const [serverHealth, setServerHealth] = useState<HealthResponse | null>(null)
  const [feed, setFeed] = useState<string[]>([])
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

  const upsertDevice = useCallback((next: Device) => {
    setDevices((prev) => {
      const idx = prev.findIndex((item) => item.deviceId === next.deviceId)
      if (idx === -1) {
        return [next, ...prev]
      }
      const clone = [...prev]
      clone[idx] = { ...clone[idx], ...next, health: { ...clone[idx].health, ...next.health } }
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

      ws.addEventListener('open', () => {
        setWsState('open')
        addFeed('Control WebSocket connected')
      })

      ws.addEventListener('message', (event) => {
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
    setSelectedDeviceId,
    sendCommand,
  }
}
