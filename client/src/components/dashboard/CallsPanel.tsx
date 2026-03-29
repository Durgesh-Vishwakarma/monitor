import type { Call } from '../../types/dashboard'

type CallsPanelProps = {
  calls: Call[]
}

function formatDuration(seconds?: number): string {
  if (!seconds) return ''
  const mins = Math.floor(seconds / 60)
  const secs = seconds % 60
  return `${mins}:${secs.toString().padStart(2, '0')}`
}

export function CallsPanel({ calls }: CallsPanelProps) {
  if (calls.length === 0) {
    return (
      <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
        <div className="flex items-center gap-2 px-4 py-2 bg-yellow-500/20 border-b border-slate-700/50">
          <span className="text-yellow-400">📞</span>
          <span className="text-sm font-medium text-white">Calls</span>
          <span className="ml-auto text-xs text-slate-500">0 calls</span>
        </div>
        <div className="p-4 text-center text-slate-500 text-sm">
          No call history received yet. Click "Sync Data" to fetch.
        </div>
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-2 bg-yellow-500/20 border-b border-slate-700/50">
        <span className="text-yellow-400">📞</span>
        <span className="text-sm font-medium text-white">Calls</span>
        <span className="ml-auto text-xs text-slate-500">{calls.length} calls</span>
      </div>
      
      <div className="max-h-64 overflow-y-auto">
        {calls.map((call) => (
          <div key={call.id} className="flex items-center gap-3 px-4 py-2 border-b border-slate-700/30 hover:bg-slate-700/20 transition-colors">
            <span className={`text-lg ${
              call.type === 'missed' || call.type === 'rejected' ? 'text-red-400' : 
              call.type === 'incoming' ? 'text-green-400' : 'text-blue-400'
            }`}>
              {call.type === 'incoming' ? '📲' : 
               call.type === 'outgoing' ? '📱' : 
               call.type === 'missed' ? '📵' : 
               call.type === 'rejected' ? '🚫' : '📞'}
            </span>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <span className="text-sm text-white truncate">{call.number}</span>
                {call.name && (
                  <span className="text-xs text-slate-400 truncate">({call.name})</span>
                )}
              </div>
              <div className="flex items-center gap-2">
                <span className={`text-[10px] px-1 py-0.5 rounded ${
                  call.type === 'incoming' ? 'bg-green-500/20 text-green-400' :
                  call.type === 'outgoing' ? 'bg-blue-500/20 text-blue-400' :
                  call.type === 'missed' ? 'bg-red-500/20 text-red-400' :
                  call.type === 'rejected' ? 'bg-orange-500/20 text-orange-400' :
                  'bg-slate-500/20 text-slate-400'
                }`}>
                  {call.type.toUpperCase()}
                </span>
                <span className="text-[10px] text-slate-500">
                  {new Date(call.timestamp).toLocaleString()}
                </span>
              </div>
            </div>
            {call.duration !== undefined && call.duration > 0 && (
              <span className="text-xs text-slate-400 font-mono">
                {formatDuration(call.duration)}
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
