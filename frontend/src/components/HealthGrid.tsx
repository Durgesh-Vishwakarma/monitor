import type { Device } from '@/types';

interface HealthGridProps {
  device: Device;
}

export function HealthGrid({ device }: HealthGridProps) {
  const getBitrateClass = (bitrate?: number) => {
    if (!bitrate) return 'br-mid';
    if (bitrate >= 128) return 'br-high';
    if (bitrate >= 64) return 'br-mid';
    return 'br-low';
  };

  return (
    <div className="grid grid-cols-3 gap-1.5 mb-2.5 md:grid-cols-2">
      {/* Battery */}
      <div className="health-item">
        <div className="text-[9px] uppercase tracking-wider font-semibold text-text-dim">
          Battery
        </div>
        <div className="text-[11px] font-mono text-text mt-0.5">
          {device.battery ?? '--'}%
          {device.isCharging && <span className="text-green-light ml-1">⚡</span>}
        </div>
      </div>

      {/* CPU */}
      <div className="health-item">
        <div className="text-[9px] uppercase tracking-wider font-semibold text-text-dim">
          CPU
        </div>
        <div className="text-[11px] font-mono text-text mt-0.5">
          {device.cpuUsage ?? '--'}%
        </div>
      </div>

      {/* Memory */}
      <div className="health-item">
        <div className="text-[9px] uppercase tracking-wider font-semibold text-text-dim">
          Memory
        </div>
        <div className="text-[11px] font-mono text-text mt-0.5">
          {device.memoryUsage ?? '--'}%
        </div>
      </div>

      {/* Signal */}
      <div className="health-item">
        <div className="text-[9px] uppercase tracking-wider font-semibold text-text-dim">
          Signal
        </div>
        <div className="text-[11px] font-mono text-text mt-0.5">
          {device.signalStrength ?? '--'} dBm
        </div>
      </div>

      {/* Bitrate */}
      <div className="health-item">
        <div className="text-[9px] uppercase tracking-wider font-semibold text-text-dim">
          Bitrate
        </div>
        <div className="text-[11px] font-mono mt-0.5">
          <span 
            className={`inline-block min-w-[46px] text-center rounded-full px-2 py-px text-[10px] font-bold tracking-wide border ${getBitrateClass(device.bitrate)} ${
              getBitrateClass(device.bitrate) === 'br-high' 
                ? 'text-green-light bg-green/15 border-green/35'
                : getBitrateClass(device.bitrate) === 'br-mid'
                  ? 'text-amber bg-amber/15 border-amber/35'
                  : 'text-rose-300 bg-red/15 border-red/35'
            }`}
          >
            {device.bitrate ?? '--'} kbps
          </span>
        </div>
      </div>

      {/* Sample Rate */}
      <div className="health-item">
        <div className="text-[9px] uppercase tracking-wider font-semibold text-text-dim">
          Sample
        </div>
        <div className="text-[11px] font-mono text-text mt-0.5">
          {device.sampleRate ? `${device.sampleRate / 1000}kHz` : '--'}
        </div>
      </div>

      {/* Buffer Health */}
      <div className="health-item">
        <div className="text-[9px] uppercase tracking-wider font-semibold text-text-dim">
          Buffer
        </div>
        <div className={`text-[11px] font-mono mt-0.5 ${
          (device.bufferHealth ?? 0) >= 80 ? 'text-green-light' : 'text-rose-300'
        }`}>
          {device.bufferHealth ?? '--'}%
        </div>
      </div>

      {/* Noise Suppression */}
      <div className="health-item">
        <div className="text-[9px] uppercase tracking-wider font-semibold text-text-dim">
          Noise Sup
        </div>
        <div className={`text-[11px] font-mono mt-0.5 ${
          device.noiseSuppressionEnabled ? 'text-green-light' : 'text-rose-300'
        }`}>
          {device.noiseSuppressionEnabled ? 'ON' : 'OFF'}
        </div>
      </div>
    </div>
  );
}
