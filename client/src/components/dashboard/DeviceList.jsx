import { memo, useMemo } from 'react';
function formatLastSeen(lastTs) {
  if (!lastTs) return '—';
  try {
    return new Date(lastTs).toLocaleTimeString([], {
      hour12: false,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    });
  } catch {
    return '—';
  }
}
function isOnline(d) {
  const health = d.health || {};
  const lastSeenAt = typeof health.lastHealthAt === 'number' ? health.lastHealthAt : null;
  const wsConnected = health.wsConnected !== false;
  if (!wsConnected) return false;
  if (!lastSeenAt) return true;
  return Date.now() - lastSeenAt < 60_000;
}
export const DeviceList = memo(function DeviceList({
  devices,
  activeAudioDeviceId,
  onListen,
  onStop,
  onTakePhoto,
  onForceReconnect,
  onForceUpdate
}) {
  const sorted = useMemo(() => {
    return [...devices].sort((a, b) => {
      const aOnline = isOnline(a) ? 1 : 0;
      const bOnline = isOnline(b) ? 1 : 0;
      if (aOnline !== bOnline) return bOnline - aOnline;
      return String(a.deviceId).localeCompare(String(b.deviceId));
    });
  }, [devices]);
  return <div className="space-y-4">
      {sorted.length === 0 ? <div className="rounded-xl border border-dark-700 bg-dark-800/40 p-6 text-center text-slate-400">
          No devices connected
        </div> : sorted.map(d => {
      const online = isOnline(d);
      const health = d.health || {};
      const micCapturing = health.micCapturing === true;
      const audioActive = activeAudioDeviceId === d.deviceId;
      return <div key={d.deviceId} className="rounded-xl border border-dark-700 bg-dark-800/40 p-4 shadow-card" style={{
        borderColor: online ? 'rgba(16,185,129,0.35)' : 'rgba(245,158,11,0.22)'
      }}>
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-bold text-white font-mono">
                      {d.deviceId}
                    </span>
                    <span className="inline-flex items-center gap-1 rounded border px-2 py-0.5 text-[10px] font-semibold" style={{
                background: online ? 'rgba(16,185,129,0.12)' : 'rgba(245,158,11,0.10)',
                borderColor: online ? 'rgba(16,185,129,0.25)' : 'rgba(245,158,11,0.25)',
                color: online ? '#34d399' : '#f59e0b'
              }}>
                      {online ? 'ONLINE' : 'OFFLINE'}
                      {audioActive && <span className="animate-pulse">• LIVE</span>}
                    </span>
                  </div>

                  <div className="mt-1 text-xs text-slate-400">
                    {d.model || 'Unknown'} · SDK {d.sdk ?? '—'}
                  </div>

                  <div className="mt-2 text-[10px] text-slate-500">
                    last seen: <span className="text-slate-300 font-mono">{formatLastSeen(health.lastHealthAt ?? null)}</span>
                  </div>

                  <div className="mt-1 text-[10px] text-slate-500">
                    mic: <span className="text-slate-300 font-semibold">{micCapturing ? 'capturing' : 'idle'}</span>
                  </div>
                </div>

                <div className="flex flex-col gap-2 w-[240px] shrink-0">
                  <div className="grid grid-cols-2 gap-2">
                    <button onClick={() => onListen(d.deviceId)} disabled={micCapturing} className="rounded-xl px-3 py-2 text-xs font-semibold transition-colors" style={{
                background: micCapturing ? 'rgba(16,185,129,0.10)' : 'rgba(16,185,129,0.18)',
                border: `1px solid ${micCapturing ? 'rgba(16,185,129,0.25)' : 'rgba(16,185,129,0.40)'}`,
                color: micCapturing ? '#34d399' : '#a7f3d0',
                opacity: !online ? 0.75 : 1,
                cursor: micCapturing ? 'not-allowed' : 'pointer'
              }}>
                      Listen
                    </button>
                    <button onClick={() => onStop(d.deviceId)} disabled={!micCapturing} className="rounded-xl px-3 py-2 text-xs font-semibold transition-colors" style={{
                background: micCapturing ? 'rgba(239,68,68,0.18)' : 'rgba(148,163,184,0.06)',
                border: `1px solid ${micCapturing ? 'rgba(239,68,68,0.40)' : 'rgba(255,255,255,0.07)'}`,
                color: micCapturing ? '#fca5a5' : '#94a3b8',
                opacity: micCapturing ? 1 : 0.65,
                cursor: micCapturing ? 'pointer' : 'not-allowed'
              }}>
                      Stop
                    </button>
                  </div>

                  <div className="grid grid-cols-1 gap-2">
                    <button onClick={() => onTakePhoto(d.deviceId)} disabled={false} className="rounded-xl px-3 py-2 text-xs font-semibold transition-colors" style={{
                background: 'rgba(96,165,250,0.14)',
                border: '1px solid rgba(96,165,250,0.35)',
                color: '#93c5fd',
                opacity: !online ? 0.75 : 1,
                cursor: 'pointer'
              }}>
                      Take Photo
                    </button>

                    <button onClick={() => onForceReconnect(d.deviceId)} disabled={false} className="rounded-xl px-3 py-2 text-xs font-semibold transition-colors" style={{
                background: 'rgba(99,102,241,0.14)',
                border: '1px solid rgba(99,102,241,0.35)',
                color: '#a5b4fc'
              }}>
                      Force Reconnect
                    </button>

                    <button onClick={() => onForceUpdate(d.deviceId)} disabled={false} className="rounded-xl px-3 py-2 text-xs font-semibold transition-colors" style={{
                background: 'rgba(167,139,250,0.14)',
                border: '1px solid rgba(167,139,250,0.35)',
                color: '#c4b5fd'
              }}>
                      Force Update
                    </button>
                  </div>
                </div>
              </div>
            </div>;
    })}
    </div>;
});