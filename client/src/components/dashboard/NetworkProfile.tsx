type NetworkProfileProps = {
  onForceToggle?: () => void
}

export function NetworkProfile({ onForceToggle }: NetworkProfileProps) {
  return (
    <div className="flex items-center justify-between px-4 py-2 rounded-lg bg-slate-800/50 border border-slate-700/50">
      <div className="flex items-center gap-3">
        <span className="px-2 py-1 text-xs bg-red-500/20 text-red-400 border border-red-500/30 rounded font-medium">
          Network Profile:
        </span>
        <span className="text-yellow-400 text-sm">
          ⚡ Low-network mode: Opus 48-96kbps, 16kHz (balanced quality/bandwidth)
        </span>
      </div>
      <button 
        onClick={onForceToggle}
        className="px-3 py-1.5 text-xs bg-teal-500/20 text-teal-400 border border-teal-500/30 rounded font-medium hover:bg-teal-500/30 transition-colors"
      >
        FORCE LOW-NETWORK: ON
      </button>
    </div>
  )
}
