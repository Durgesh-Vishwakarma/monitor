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
              <div key={rec.id} className="flex flex-col gap-2 p-3 rounded bg-slate-700/30 hover:bg-slate-700/50 transition-colors">
                <div className="flex items-center justify-between">
                  <div>
                    <div className="text-sm font-medium text-white">{rec.filename}</div>
                    <div className="text-xs text-slate-400 mt-0.5">
                      {new Date(rec.timestamp).toLocaleString()} • {formatSize(rec.size)}
                    </div>
                  </div>
                </div>
                <audio 
                  controls 
                  src={rec.url} 
                  className="w-full h-8 mt-1 block rounded outline-none" 
                  preload="metadata"
                />
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
