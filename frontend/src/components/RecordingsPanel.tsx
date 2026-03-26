'use client';

import { useEffect, useState } from 'react';

interface Recording {
  name: string;
  url: string;
  size?: string;
}

export function RecordingsPanel() {
  const [recordings, setRecordings] = useState<Recording[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchRecordings();
  }, []);

  const fetchRecordings = async () => {
    try {
      const response = await fetch('/api/recordings');
      if (response.ok) {
        const data = await response.json();
        setRecordings(data);
      }
    } catch (error) {
      console.error('Failed to fetch recordings:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="panel">
      <div className="flex items-center justify-between px-4 pt-4">
        <h3 className="text-[11px] font-bold text-text-muted uppercase tracking-widest flex items-center gap-2">
          <span className="text-[15px]">🎙</span>
          Recordings
        </h3>
        <button 
          onClick={fetchRecordings}
          className="text-[10px] text-text-dim hover:text-text-muted transition-colors"
        >
          ↻ Refresh
        </button>
      </div>
      
      <div className="p-3">
        {loading ? (
          <div className="text-center py-6 text-text-dim text-xs">
            Loading...
          </div>
        ) : recordings.length === 0 ? (
          <div className="text-center py-6 text-text-dim text-xs">
            No recordings yet
          </div>
        ) : (
          <div className="flex flex-col gap-1.5">
            {recordings.map((rec, i) => (
              <div 
                key={i}
                className="flex items-center justify-between bg-bg2 border border-border rounded-lg px-2.5 py-1.5 hover:border-border2 transition-colors gap-2"
              >
                <a 
                  href={rec.url}
                  download
                  className="text-teal-light text-[11px] font-mono truncate max-w-[170px] hover:text-violet-light hover:underline"
                >
                  {rec.name}
                </a>
                {rec.size && (
                  <span className="text-text-dim text-[10px] font-mono flex-shrink-0">
                    {rec.size}
                  </span>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
