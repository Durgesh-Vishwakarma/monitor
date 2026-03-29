import type { Call } from '../../types/dashboard'

type CallsPanelProps = {
  calls: Call[]
}

export function CallsPanel({ calls }: CallsPanelProps) {
  return (
    <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-2 bg-yellow-500/20 border-b border-slate-700/50">
        <span className="text-yellow-400">📞</span>
        <span className="text-sm font-medium text-white">Calls</span>
      </div>
      
      <div className="p-4">
        {calls.length === 0 ? (
          <div className="text-center text-slate-500 text-sm py-4">No call history</div>
        ) : (
          <div className="space-y-2">
            {calls.map((call) => (
              <div key={call.id} className="flex items-center gap-3 p-2 rounded bg-slate-700/30">
                <span className={call.type === 'missed' ? 'text-red-400' : call.type === 'incoming' ? 'text-green-400' : 'text-blue-400'}>
                  {call.type === 'incoming' ? '📲' : call.type === 'outgoing' ? '📱' : '📵'}
                </span>
                <div className="flex-1">
                  <div className="text-sm text-white">{call.number}</div>
                  <div className="text-xs text-slate-500">{new Date(call.timestamp).toLocaleString()}</div>
                </div>
                {call.duration && (
                  <span className="text-xs text-slate-400">{Math.floor(call.duration / 60)}:{(call.duration % 60).toString().padStart(2, '0')}</span>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
