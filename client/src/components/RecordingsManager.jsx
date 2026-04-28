import React, { useCallback, useEffect, useState } from 'react';
import { apiUrl } from '../lib/helpers';

export default function RecordingsManager({ deviceId, ws }) {
  const [recordings, setRecordings] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [fileStatuses, setFileStatuses] = useState({});

  const fetchRecordings = useCallback(() => {
    if (!deviceId) return;

    setIsLoading(true);
    fetch(apiUrl(`/api/recordings?deviceId=${encodeURIComponent(deviceId)}`))
      .then((response) => {
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        return response.json();
      })
      .then((data) => {
        setRecordings(Array.isArray(data) ? data : []);
      })
      .catch((error) => {
        console.error('Error fetching recordings', error);
        setRecordings([]);
      })
      .finally(() => {
        setIsLoading(false);
      });
  }, [deviceId]);

  useEffect(() => {
    setRecordings([]);
    fetchRecordings();

    const intervalId = window.setInterval(fetchRecordings, 30000);
    return () => window.clearInterval(intervalId);
  }, [deviceId, fetchRecordings]);

  useEffect(() => {
    if (!ws) return;

    const handleMessage = (event) => {
      if (typeof event.data !== 'string') return;

      try {
        const data = JSON.parse(event.data);
        if (data.type === 'recording_saved' && data.deviceId === deviceId) {
          fetchRecordings();
        }
        if (data.type === 'command_ack' && data.command === 'delete_recording' && data.status === 'success' && data.deviceId === deviceId) {
          const deletedFilename = data.detail || '';
          setRecordings(prev => prev.filter(r => {
            const displayName = r.originalName || r.name;
            return deletedFilename !== displayName && deletedFilename !== r.name;
          }));
          setFileStatuses(s => {
            const newStatus = { ...s };
            delete newStatus[deletedFilename];
            return newStatus;
          });
        }
      } catch (_error) {
        // Ignore malformed messages.
      }
    };

    ws.addEventListener('message', handleMessage);
    return () => {
      ws.removeEventListener('message', handleMessage);
    };
  }, [ws, deviceId, fetchRecordings]);

  const isWsConnected = ws && ws.readyState === WebSocket.OPEN;

  const handleForceScan = () => {
    if (!isWsConnected) return;
    ws.send(JSON.stringify({ cmd: 'scan_recordings', deviceId }));
    setIsLoading(true);
    window.setTimeout(fetchRecordings, 5000);
  };

  const handleDownloadAndClean = async (recording) => {
    if (!isWsConnected) return;
    const fileName = recording.originalName || recording.name;
    setFileStatuses(s => ({ ...s, [fileName]: 'downloading' }));
    try {
      const res = await fetch(apiUrl(recording.url));
      if (!res.ok) throw new Error("Failed to fetch file from server");
      const blob = await res.blob();

      if (window.showSaveFilePicker) {
        const handle = await window.showSaveFilePicker({ suggestedName: fileName });
        const writable = await handle.createWritable();
        await writable.write(blob);
        await writable.close();
      } else {
        const blobUrl = window.URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = blobUrl;
        link.download = fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.setTimeout(() => window.URL.revokeObjectURL(blobUrl), 10000);
      }

      ws.send(JSON.stringify({ cmd: "delete_recording", deviceId: deviceId, filename: fileName }));
      setFileStatuses(s => ({ ...s, [fileName]: 'deleting' }));
    } catch (e) {
      if (e.name !== 'AbortError') {
        console.error("Error saving file:", e);
        setFileStatuses(s => ({ ...s, [fileName]: 'error' }));
        setTimeout(() => {
          setFileStatuses(s => {
            const newStatus = { ...s };
            delete newStatus[fileName];
            return newStatus;
          });
        }, 3000);
      } else {
        // User cancelled save dialog
        setFileStatuses(s => {
          const newStatus = { ...s };
          delete newStatus[fileName];
          return newStatus;
        });
      }
    }
  };

  const handleDeleteRemote = (recording) => {
    if (!isWsConnected) return;
    const fileName = recording.originalName || recording.name;
    const confirmed = window.confirm(`Delete \"${fileName}\" from the remote device?`);
    if (!confirmed) return;
    setFileStatuses(s => ({ ...s, [fileName]: 'deleting' }));
    ws.send(JSON.stringify({ cmd: 'delete_recording', deviceId, filename: fileName }));
  };

  if (!deviceId) return null;

  return (
    <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-2 bg-cyan-500/20 border-b border-slate-700/50">
        <span className="text-cyan-400">🎙</span>
        <span className="text-sm font-medium text-white">Call Recordings</span>
        <span className="ml-auto text-xs text-slate-500">
          <span className={`inline-block w-2 h-2 rounded-full mr-2 ${isWsConnected ? 'bg-emerald-400' : 'bg-red-400 animate-pulse'}`} />
          {isWsConnected ? 'Connected' : 'Offline'}
        </span>
      </div>

      <div className="p-4">
        <div className="mb-4 rounded-lg border border-slate-700/50 bg-slate-900/40 p-4">
          <p className="text-[10px] font-bold uppercase tracking-widest text-slate-400 mb-2">
            Remote Recorder Actions
          </p>

          <div className="flex flex-wrap gap-2 mt-1">
            <button
              onClick={handleForceScan}
              disabled={!isWsConnected}
              className="rounded-lg border border-emerald-500/30 bg-emerald-500/15 px-3 py-2 text-sm font-semibold text-emerald-300 transition-colors hover:bg-emerald-500/25 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Force Scan Now
            </button>
            <button
              onClick={fetchRecordings}
              className="rounded-lg border border-slate-700 bg-slate-800 px-3 py-2 text-sm font-semibold text-white transition-colors hover:bg-slate-700 inline-flex items-center gap-2"
            >
              {isLoading ? 'Refreshing...' : 'Refresh'}
            </button>
          </div>
        </div>

        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-medium text-white">Saved Files</h3>
          <span className="text-xs text-slate-500">{recordings.length} items</span>
        </div>

        {recordings.length === 0 ? (
          <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 p-4 text-center text-slate-500 text-sm">
            No recordings found.
          </div>
        ) : (
          <ul className="space-y-3 max-h-64 overflow-y-auto pr-1">
            {recordings.map((recording, index) => {
              const status = fileStatuses[recording.originalName || recording.name];
              const isFileLoading = !!status;
              const isDeleting = status === 'deleting';
              const isDownloading = status === 'downloading';
              return (
                <li key={recording.name || index} className="rounded-lg border border-slate-700/50 bg-slate-800/30 p-3 hover:bg-slate-700/20 transition-colors">
                  <div className="flex flex-col gap-2 mb-3">
                    <div className="flex items-start justify-between gap-3">
                      <p className="min-w-0 font-mono text-sm text-white truncate" title={recording.originalName || recording.name}>{recording.originalName || recording.name}</p>
                      <span className="text-[11px] text-slate-500 whitespace-nowrap">
                        {(Number(recording.size || 0) / 1024 / 1024).toFixed(2)} MB
                      </span>
                    </div>
                    <div className="flex items-center justify-between gap-3 text-[11px] text-slate-500">
                      <span>{recording.ts ? new Date(recording.ts).toLocaleString() : 'Unknown date'}</span>
                      <div className="flex items-center gap-3">
                        <button
                          onClick={() => handleDownloadAndClean(recording)}
                          disabled={!isWsConnected || isFileLoading}
                          className="font-semibold flex items-center gap-1 transition-colors disabled:opacity-50 disabled:cursor-wait data-[status=deleting]:text-amber-400 data-[status=error]:text-red-400 data-[status=ok]:text-emerald-400 text-emerald-400 hover:text-emerald-300"
                          data-status={status}
                        >
                          {isDownloading && <svg className="w-4 h-4 animate-spin" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>}
                          {!isDownloading && <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4" /></svg>}
                          {isDownloading && 'Saving...'}
                          {isDeleting && 'Cleaning...'}
                          {status === 'error' && 'Error!'}
                          {!status && 'Save to PC & Clean'}
                        </button>

                        <button
                          onClick={() => handleDeleteRemote(recording)}
                          disabled={!isWsConnected || isFileLoading}
                          className="font-semibold text-rose-400 hover:text-rose-300 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                          Delete
                        </button>
                      </div>
                    </div>
                  </div>

                  {/* Use src directly so the browser auto-detects native formats (.amr, .m4a, .mp3, etc) */}
                  <audio controls preload="metadata" className="w-full h-10 outline-none" src={apiUrl(recording.url)} />
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}