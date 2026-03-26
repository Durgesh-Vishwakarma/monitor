'use client';

import { useState, useRef, useEffect } from 'react';

declare global {
  interface Window {
    QRCode: any;
  }
}

export function QRModal() {
  const [isOpen, setIsOpen] = useState(false);
  const [serverUrl, setServerUrl] = useState('');
  const qrCanvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (typeof window !== 'undefined') {
      // Use current URL as server URL
      const url = window.location.origin;
      setServerUrl(url);
    }
  }, []);

  useEffect(() => {
    if (isOpen && serverUrl && qrCanvasRef.current && window.QRCode) {
      window.QRCode.toCanvas(qrCanvasRef.current, serverUrl, {
        width: 200,
        margin: 2,
        color: {
          dark: '#e2e8f0',
          light: '#0f1123',
        },
      });
    }
  }, [isOpen, serverUrl]);

  return (
    <>
      {/* Trigger Button - Fixed position */}
      <button
        onClick={() => setIsOpen(true)}
        className="fixed bottom-6 right-6 w-14 h-14 bg-gradient-to-br from-violet to-teal rounded-2xl flex items-center justify-center text-2xl shadow-glow-violet hover:scale-105 transition-transform z-40"
        title="Show QR Code"
      >
        📲
      </button>

      {/* Modal */}
      {isOpen && (
        <div 
          className="fixed inset-0 bg-black/80 flex items-center justify-center z-50 p-4"
          onClick={() => setIsOpen(false)}
        >
          <div 
            className="bg-card border border-border2 rounded-2xl p-6 max-w-sm w-full"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-bold bg-gradient-to-r from-violet-light to-teal-light bg-clip-text text-transparent">
                Connect Device
              </h2>
              <button 
                className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-white/10 transition-colors text-text-dim"
                onClick={() => setIsOpen(false)}
              >
                ✕
              </button>
            </div>

            <p className="text-sm text-text-muted mb-4">
              Scan this QR code with the MicMonitor app to connect your device.
            </p>

            <div className="flex justify-center mb-4">
              <div className="bg-bg2 p-4 rounded-xl border border-border">
                <canvas ref={qrCanvasRef} />
              </div>
            </div>

            <div className="bg-bg2 rounded-lg p-3 border border-border">
              <div className="text-[10px] text-text-dim uppercase tracking-wider mb-1">
                Server URL
              </div>
              <code className="text-xs font-mono text-teal-light break-all">
                {serverUrl}
              </code>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
