import { StatCard } from '../ui/StatCard'
import type { Device, HealthResponse } from '../../types/dashboard'

type ServerSnapshotProps = {
  serverHealth: HealthResponse | null
  devices: Device[]
}

export function ServerSnapshot({ serverHealth, devices }: ServerSnapshotProps) {
  const isHealthy = serverHealth?.status === 'ok'
  const deviceCount = serverHealth?.devices ?? devices.length
  const lastPoll = serverHealth ? new Date(serverHealth.ts).toLocaleTimeString() : 'n/a'

  return (
    <div className="rounded-lg border border-dark-700 bg-dark-800 p-4 shadow-card">
      <h2 className="mb-3 text-sm font-semibold text-white">
        Server Status
      </h2>
      
      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard
          label="Backend Status"
          value={isHealthy ? 'Online' : 'Offline'}
          icon={
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M12 5l7 7-7 7" />
            </svg>
          }
        />
        
        <StatCard
          label="Connected Devices"
          value={deviceCount}
          icon={
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z" />
            </svg>
          }
        />
        
        <StatCard
          label="Last Health Poll"
          value={lastPoll}
          icon={
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          }
        />
      </div>
    </div>
  )
}
