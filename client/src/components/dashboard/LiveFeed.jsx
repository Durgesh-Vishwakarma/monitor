import { Panel } from '../ui/Panel';
export function LiveFeed({
  feed
}) {
  return <Panel title="Live Feed" subtitle="Real-time event stream">
      <div className="max-h-[380px] overflow-auto rounded border border-dark-700 bg-dark-900/60 p-2">
        {feed.length === 0 ? <div className="flex h-32 items-center justify-center">
            <p className="text-sm text-slate-400">No events yet</p>
          </div> : <ul className="space-y-1">
            {feed.map((line, idx) => <li key={`${line}-${idx}`} className="rounded border border-dark-700/50 bg-dark-800/50 px-2 py-1.5 font-mono text-xs text-slate-300 hover:bg-dark-700/70 whitespace-pre-wrap break-words">
                {line}
              </li>)}
          </ul>}
      </div>
    </Panel>;
}