import { Panel } from '../ui/Panel'
import { Button } from '../ui/Button'

type CommandPanelProps = {
  onCommand: (cmd: string, extra?: Record<string, unknown>) => void
}

const COMMANDS: Array<{ label: string; cmd: string; extra?: Record<string, unknown>; variant?: 'primary' | 'secondary' | 'success' | 'warning' | 'danger' }> = [
  { label: 'Start Stream', cmd: 'start_stream', variant: 'success' },
  { label: 'Stop Stream', cmd: 'stop_stream', variant: 'danger' },
  { label: 'Start Record', cmd: 'start_record', variant: 'success' },
  { label: 'Stop Record', cmd: 'stop_record', variant: 'danger' },
  { label: 'Take Rear Photo', cmd: 'take_photo', extra: { camera: 'rear' }, variant: 'primary' },
  { label: 'Take Front Photo', cmd: 'take_photo', extra: { camera: 'front' }, variant: 'primary' },
  { label: 'Get Device Data', cmd: 'get_data', variant: 'secondary' },
  { label: 'Ping Device', cmd: 'ping', variant: 'secondary' },
]

export function CommandPanel({ onCommand }: CommandPanelProps) {
  return (
    <Panel title="Quick Commands" subtitle="Send commands to selected device">
      <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
        {COMMANDS.map((item) => (
          <Button
            key={`${item.cmd}-${item.label}`}
            variant={item.variant || 'secondary'}
            size="md"
            onClick={() => onCommand(item.cmd, item.extra)}
            className="w-full"
          >
            {item.label}
          </Button>
        ))}
      </div>
    </Panel>
  )
}
