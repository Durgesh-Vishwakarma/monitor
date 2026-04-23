export function SMSPanel({
  messages
}) {
  if (messages.length === 0) {
    return <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
        <div className="flex items-center gap-2 px-4 py-2 bg-emerald-500/20 border-b border-slate-700/50">
          <span className="text-emerald-400">●</span>
          <span className="text-sm font-medium text-white">SMS</span>
          <span className="ml-auto text-xs text-slate-500">0 messages</span>
        </div>
        <div className="p-4 text-center text-slate-500 text-sm">
          No SMS data received yet. Click "Sync Data" to fetch.
        </div>
      </div>;
  }
  return <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-2 bg-emerald-500/20 border-b border-slate-700/50">
        <span className="text-emerald-400">●</span>
        <span className="text-sm font-medium text-white">SMS</span>
        <span className="ml-auto text-xs text-slate-500">{messages.length} messages</span>
      </div>
      
      <div className="max-h-64 overflow-y-auto">
        {messages.map(msg => <div key={msg.id} className="border-b border-slate-700/30 px-4 py-2 hover:bg-slate-700/20 transition-colors">
            <div className="flex items-center gap-2 mb-1">
              <span className="font-semibold text-sm text-white truncate max-w-[150px]">{msg.sender}</span>
              <span className={`px-1.5 py-0.5 text-[9px] rounded ${msg.type === 'inbox' ? 'bg-blue-500/20 text-blue-400' : msg.type === 'sent' ? 'bg-green-500/20 text-green-400' : 'bg-slate-500/20 text-slate-400'}`}>
                {msg.type.toUpperCase()}
              </span>
              {!msg.read && msg.type === 'inbox' && <span className="px-1.5 py-0.5 text-[9px] bg-red-500/20 text-red-400 rounded">UNREAD</span>}
              <span className="ml-auto text-[10px] text-slate-500">
                {new Date(msg.timestamp).toLocaleString()}
              </span>
            </div>
            <p className="text-xs text-slate-400 line-clamp-2">{msg.body}</p>
          </div>)}
      </div>
    </div>;
}