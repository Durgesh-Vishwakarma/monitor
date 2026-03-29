import { Panel } from '../ui/Panel'
import { Badge } from '../ui/Badge'
import type { Device } from '../../types/dashboard'

type DeviceSelectorProps = {
  devices: Device[]
  selectedDeviceId: string
  selectedDevice: Device | null
  onSelect: (deviceId: string) => void
}

export function DeviceSelector({
  devices,
  selectedDeviceId,
  selectedDevice,
  onSelect,
}: DeviceSelectorProps) {
  const micStatus = selectedDevice?.health?.micCapturing ? 'capturing' : 'idle'
  const batteryPct = selectedDevice?.health?.batteryPct

  return (
    <Panel title="Device Selector" subtitle="Select a device to control">
      {/* Device Cards */}
      <div className="mb-3 grid gap-2 sm:grid-cols-2">
        {devices.length === 0 ? (
          <div className="col-span-2 rounded border border-dark-700 bg-dark-900/50 p-4 text-center text-sm text-slate-400">
            No devices connected
          </div>
        ) : (
          devices.map((device) => (
            <button
              key={device.deviceId}
              onClick={() => onSelect(device.deviceId)}
              className={`
                rounded border p-3 text-left transition-colors
                ${
                  selectedDeviceId === device.deviceId
                    ? 'border-blue-500 bg-blue-500/10'
                    : 'border-dark-700 bg-dark-900/50 hover:border-dark-600'
                }
              `.trim()}
            >
              <div className="text-sm font-medium text-white">{device.deviceId}</div>
              <div className="mt-1 text-xs text-slate-400">{device.model || 'Unknown model'}</div>
            </button>
          ))
        )}
      </div>

      {selectedDevice && (
        <div className="space-y-2">
          <div className="rounded border border-dark-700 bg-dark-900/50 p-3">
            <h3 className="mb-2 text-xs font-medium text-slate-300">
              Device Info
            </h3>
            <dl className="grid gap-2 text-sm">
              <div className="flex justify-between">
                <dt className="text-slate-400">Model</dt>
                <dd className="font-medium text-white">{selectedDevice.model || 'Unknown'}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-400">SDK</dt>
                <dd className="font-medium text-white">{selectedDevice.sdk ?? 'n/a'}</dd>
              </div>
            </dl>
          </div>

          <div className="rounded border border-dark-700 bg-dark-900/50 p-3">
            <h3 className="mb-2 text-xs font-medium text-slate-300">
              Status
            </h3>
            <dl className="grid gap-2 text-sm">
              <div className="flex justify-between items-center">
                <dt className="text-slate-400">Microphone</dt>
                <dd>
                  <Badge variant={micStatus === 'capturing' ? 'success' : 'default'}>
                    {micStatus}
                  </Badge>
                </dd>
              </div>
              <div className="flex justify-between items-center">
                <dt className="text-slate-400">Battery</dt>
                <dd className="font-medium text-white">
                  {batteryPct == null ? 'n/a' : `${batteryPct}%`}
                </dd>
              </div>
            </dl>
          </div>
        </div>
      )}
    </Panel>
  )
}
