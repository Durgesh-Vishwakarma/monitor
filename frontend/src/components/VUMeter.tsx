interface VUMeterProps {
  level: number; // 0-100
}

export function VUMeter({ level }: VUMeterProps) {
  const clampedLevel = Math.min(100, Math.max(0, level));
  const db = level > 0 ? (20 * Math.log10(level / 100)).toFixed(1) : '-∞';
  const isClipping = level > 95;

  return (
    <div className="flex items-center gap-2 mb-2.5">
      <div className="flex-1 h-[7px] bg-bg2 border border-border rounded-full overflow-hidden">
        <div 
          className="h-full rounded-full transition-[width] duration-75"
          style={{ 
            width: `${clampedLevel}%`,
            background: 'linear-gradient(90deg, #14b8a6 0%, #f59e0b 72%, #f43f5e 94%)'
          }}
        />
      </div>
      <span className="font-mono text-[10px] text-text-dim min-w-[56px] text-right">
        {db} dB
      </span>
      <span 
        className={`text-[10px] font-extrabold text-red tracking-wider ${
          isClipping ? 'inline animate-clip-blink' : 'hidden'
        }`}
      >
        CLIP!
      </span>
    </div>
  );
}
