interface ConnectionStatusProps {
  connected: boolean;
}

export function ConnectionStatus({ connected }: ConnectionStatusProps) {
  return (
    <div 
      className={`flex items-center gap-2 bg-surface border rounded-full px-4 py-1.5 text-xs font-semibold transition-colors duration-300 ${
        connected 
          ? 'border-green/40 text-green-light' 
          : 'border-border text-text-dim'
      }`}
    >
      <div 
        className={`w-2 h-2 rounded-full transition-colors duration-300 ${
          connected 
            ? 'bg-green shadow-glow-green animate-pulse' 
            : 'bg-red'
        }`}
      />
      {connected ? 'Connected' : 'Disconnected'}
    </div>
  );
}
