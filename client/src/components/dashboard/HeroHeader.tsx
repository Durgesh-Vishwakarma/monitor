import { Badge } from '../ui/Badge'
import type { WsState } from '../../types/dashboard'

type HeroHeaderProps = {
  wsState: WsState
}

const wsStateVariant = (state: WsState) => {
  if (state === 'open') return 'success'
  if (state === 'connecting') return 'warning'
  return 'error'
}

export function HeroHeader({ wsState }: HeroHeaderProps) {
  return (
    <div className="flex items-center justify-between">
      <div className="flex items-center gap-4">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-red-500">
          <svg className="h-5 w-5 text-white" fill="currentColor" viewBox="0 0 24 24">
            <path d="M12 2L2 7v10c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V7l-10-5z" />
          </svg>
        </div>
        <div>
          <h1 className="text-lg font-semibold text-white">MicMonitor Dashboard</h1>
          <p className="text-xs text-slate-400">Device monitoring and control</p>
        </div>
      </div>

      <div className="flex items-center gap-3">
        <Badge variant={wsStateVariant(wsState)} pulse={wsState === 'open'}>
          Connection: {wsState}
        </Badge>
      </div>
    </div>
  )
}
