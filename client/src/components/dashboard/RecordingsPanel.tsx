import type { Recording } from '../../types/dashboard'

type RecordingsPanelProps = {
  recordings: Recording[]
}

export function RecordingsPanel({ recordings }: RecordingsPanelProps) {
  const formatSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  }

  const formatDuration = (seconds: number) => {
    const mins = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${mins}:${secs.toString().padStart(2, '0')}`
  }

  return (
    <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-2 bg-orange-500/20 border-b border-slate-700/50">
        <span className="text-orange-400">🎙</span>
        <span className="text-sm font-medium text-white">RECORDINGS</span>
      </div>
      
      <div className="p-4 min-h-[120px]">
        {recordings.length === 0 ? (
          <div className="text-center text-slate-500 text-sm py-8">No recordings yet</div>
        ) : (
          <div className="space-y-2">
            {recordings.map((rec) => (
              <div key={rec.id} className="flex items-center gap-3 p-2 rounded bg-slate-700/30 hover:bg-slate-700/50 cursor-pointer transition-colors">
                <button className="w-8 h-8 rounded-full bg-emerald-500/20 text-emerald-400 flex items-center justify-center hover:bg-emerald-500/30">
                  ▶
                </button>
                <div className="flex-1">
                  <div className="text-sm text-white">{rec.filename}</div>
                  <div className="text-xs text-slate-500">
                    {formatDuration(rec.duration)} • {formatSize(rec.size)}
                  </div>
                </div>
                <span className="text-xs text-slate-500">{new Date(rec.timestamp).toLocaleDateString()}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
