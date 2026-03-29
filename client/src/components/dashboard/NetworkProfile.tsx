type NetworkProfileProps = {
  lowNetwork?: boolean
  streamCodec?: string
  streamCodecMode?: string
  onForceToggle?: () => void
}

export function NetworkProfile({ lowNetwork = false, streamCodec, streamCodecMode, onForceToggle }: NetworkProfileProps) {
  const isPcm = streamCodec === 'pcm' 
  return (
    <div className="flex items-center justify-between px-4 py-2 rounded-lg bg-slate-800/50 border border-slate-700/50">
      <div className="flex items-center gap-3">
        <span className={`px-2 py-1 text-xs border rounded font-medium ${lowNetwork ? 'bg-red-500/20 text-red-400 border-red-500/30' : 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30'}`}>
          Network Profile:
        </span>
        <span className={`text-sm ${lowNetwork ? 'text-yellow-400' : 'text-slate-300'}`}>
          {lowNetwork 
            ? '⚡ Low-network mode: Opus 48-96kbps, 16kHz (balanced bandwidth)' 
            : `📡 High-Quality mode: ${streamCodecMode === 'auto' ? 'HQ Opus' : isPcm ? 'Uncompressed PCM' : streamCodec} (max fidelity)`}
        </span>
      </div>
      <button 
        onClick={onForceToggle}
        className={`px-3 py-1.5 text-xs border rounded font-medium transition-colors ${
          lowNetwork 
            ? 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30 hover:bg-yellow-500/30' 
            : 'bg-slate-700/50 text-slate-400 border-slate-600 hover:bg-slate-700'
        }`}
      >
        FORCE LOW-NETWORK: {lowNetwork ? 'ON' : 'OFF'}
      </button>
    </div>
  )
}
