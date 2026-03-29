type EventLogProps = {
  events: string[]
}

export function EventLog({ events }: EventLogProps) {
  // Demo events if empty
  const displayEvents = events.length > 0 ? events : [
    '🔵 Data received from 664b1681',
    '📢 CMD 664b1681: stream_codec (success) - pcm',
    '📢 CMD 664b1681: streaming_auto (success) - realtime',
    '📢 CMD 664b1681: set_low_network (success) - off',
    '📢 CMD 664b1681: photo_ai (success) - on',
    '⚙️ ACK 664b1681: ack:photo_ai=on',
    '📢 CMD 664b1681: photo_night (success) - off',
    '⚙️ ACK 664b1681: ACK:photo_night=off',
    '📢 CMD 504a1a81: photo_quality (success) - normal',
  ]

  const getEventColor = (event: string) => {
    if (event.includes('success')) return 'text-emerald-400'
    if (event.includes('error') || event.includes('failed')) return 'text-red-400'
    if (event.includes('ACK') || event.includes('ack')) return 'text-yellow-400'
    if (event.includes('Data received')) return 'text-blue-400'
    return 'text-slate-400'
  }

  const getTimestamp = () => {
    const now = new Date()
    return `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}`
  }

  return (
    <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-2 bg-slate-700/50 border-b border-slate-700/50">
        <span className="text-slate-400">📋</span>
        <span className="text-sm font-medium text-white">EVENT LOG</span>
      </div>
      
      <div className="p-3 max-h-48 overflow-y-auto font-mono text-xs">
        {displayEvents.map((event, idx) => (
          <div key={idx} className="flex gap-2 py-0.5 hover:bg-slate-700/20">
            <span className="text-slate-600 shrink-0">{getTimestamp()}</span>
            <span className={getEventColor(event)}>{event}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
