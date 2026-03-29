import type { SMS } from '../../types/dashboard'

type SMSPanelProps = {
  messages: SMS[]
}

export function SMSPanel({ messages }: SMSPanelProps) {
  // Demo data if no messages
  const demoMessages: SMS[] = messages.length > 0 ? messages : [
    {
      id: '1',
      sender: 'AZ-AIRFSC-P',
      body: 'Update for Mobile 797703903 - You\'re pre-approved for a loan from Airtel Finance for up to Rs. 8,00,000. Apply now https://l.airtel.in/LOAN28',
      timestamp: new Date().toISOString(),
      read: false,
    },
    {
      id: '2',
      sender: 'AD-MAXFSN-P',
      body: 'The stylish trio takes over Lakme Fashion Week, marking 20 years of Max Fashion! Check out the runway fits of Alaya, Kalki, and Siddhant here: bit.ly/40VUf25',
      timestamp: new Date().toISOString(),
      read: false,
    },
    {
      id: '3',
      sender: 'AD-AIRTEL-P',
      body: 'Congratulations! Now you have FREE access to movies, TV shows and Live channels with your Airtel recharge. Download Airtel Xstream Play and start watching. https://open.airtelxstream.in/#FREE_MOVIES$',
      timestamp: new Date().toISOString(),
      read: false,
    },
    {
      id: '4',
      sender: 'AD-650025-P',
      body: 'Never run out of data during important moments. Go Limitless with Unlimited data on Airtel Postpaid, plans starting at Rs. 449. Upgrade now https://l.airtel.in/goldenshort',
      timestamp: new Date().toISOString(),
      read: false,
    },
  ]

  return (
    <div className="rounded-lg border border-slate-700/50 bg-slate-800/30 overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-2 bg-emerald-500/20 border-b border-slate-700/50">
        <span className="text-emerald-400">●</span>
        <span className="text-sm font-medium text-white">SMS</span>
      </div>
      
      <div className="max-h-48 overflow-y-auto">
        {demoMessages.map((msg) => (
          <div key={msg.id} className="border-b border-slate-700/30 px-4 py-2 hover:bg-slate-700/20 transition-colors">
            <div className="flex items-center gap-2 mb-1">
              <span className="font-semibold text-sm text-white">{msg.sender}</span>
              <span className="px-1.5 py-0.5 text-[9px] bg-blue-500/20 text-blue-400 rounded">INBOX</span>
              <span className="px-1.5 py-0.5 text-[9px] bg-red-500/20 text-red-400 rounded">UNREAD</span>
              <span className="ml-auto text-[10px] text-slate-500">
                {new Date(msg.timestamp).toLocaleString()}
              </span>
            </div>
            <p className="text-xs text-slate-400 line-clamp-2">{msg.body}</p>
          </div>
        ))}
      </div>
    </div>
  )
}
