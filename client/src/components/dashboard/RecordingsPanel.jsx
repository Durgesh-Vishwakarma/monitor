export function RecordingsPanel({
  recordings
}) {
  const formatSize = bytes => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };
  const formatDuration = rec => {
    if (!rec.duration) return '';
    const s = Math.round(rec.duration);
    if (s < 60) return `${s}s`;
    return `${Math.floor(s / 60)}m ${s % 60}s`;
  };
  return <div className="rounded-2xl overflow-hidden flex flex-col" style={{
    background: 'rgba(10,14,26,0.7)',
    border: '1px solid rgba(251,146,60,0.15)',
    backdropFilter: 'blur(12px)',
    boxShadow: '0 4px 32px rgba(0,0,0,0.4)'
  }}>
      {/* Header */}
      <div className="flex items-center gap-3 px-4 py-3" style={{
      background: 'rgba(251,146,60,0.06)',
      borderBottom: '1px solid rgba(251,146,60,0.12)'
    }}>
        <div className="w-7 h-7 rounded-lg flex items-center justify-center text-sm" style={{
        background: 'rgba(251,146,60,0.15)',
        border: '1px solid rgba(251,146,60,0.3)'
      }}>
          🎙
        </div>
        <span className="text-xs font-bold uppercase tracking-widest text-slate-300">Recordings</span>
        <div className="ml-auto flex items-center gap-2">
          {recordings.length > 0 && <span className="text-[10px] font-bold px-2 py-0.5 rounded-full" style={{
          background: 'rgba(251,146,60,0.2)',
          border: '1px solid rgba(251,146,60,0.35)',
          color: '#fb923c'
        }}>
              {recordings.length}
            </span>}
        </div>
      </div>

      {/* Content */}
      <div className="p-4 max-h-64 overflow-y-auto">
        {recordings.length === 0 ? <div className="flex flex-col items-center justify-center py-8 text-center">
            <div className="text-3xl mb-3 opacity-20">🎙</div>
            <div className="text-sm text-slate-500">No recordings yet</div>
            <div className="text-xs text-slate-600 mt-1">Use «Record» to start capturing</div>
          </div> : <div className="space-y-3">
            {recordings.map(rec => <div key={rec.id} className="rounded-xl p-3 group transition-all duration-200" style={{
          background: 'rgba(251,146,60,0.05)',
          border: '1px solid rgba(251,146,60,0.12)'
        }}>
                <div className="flex items-start justify-between gap-2 mb-2">
                  <div className="min-w-0">
                    <div className="text-sm font-semibold text-white truncate">{rec.filename}</div>
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="text-[10px] text-slate-500">
                        {new Date(rec.timestamp).toLocaleString()}
                      </span>
                      <span className="text-[10px] px-1.5 py-0.5 rounded" style={{
                  background: 'rgba(251,146,60,0.12)',
                  color: '#fb923c'
                }}>
                        {formatSize(rec.size)}
                      </span>
                      {rec.duration && <span className="text-[10px] text-slate-600">{formatDuration(rec)}</span>}
                    </div>
                  </div>
                </div>
                <audio controls src={rec.url} className="w-full h-8 rounded-lg" preload="metadata" style={{
            accentColor: '#fb923c'
          }} />
              </div>)}
          </div>}
      </div>
    </div>;
}