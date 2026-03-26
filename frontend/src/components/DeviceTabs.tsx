'use client';

import { useState } from 'react';
import type { Device } from '@/types';

interface DeviceTabsProps {
  device: Device;
}

type TabType = 'calls' | 'sms' | 'contacts';

export function DeviceTabs({ device }: DeviceTabsProps) {
  const [activeTab, setActiveTab] = useState<TabType>('calls');

  const tabs: { id: TabType; label: string }[] = [
    { id: 'calls', label: 'Calls' },
    { id: 'sms', label: 'SMS' },
    { id: 'contacts', label: 'Contacts' },
  ];

  return (
    <div className="mb-2.5">
      {/* Tab Bar */}
      <div className="flex gap-0.5 flex-wrap bg-bg2 rounded-xl p-1 mb-2.5 border border-border">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            className={`flex-1 rounded-lg px-2 py-1.5 text-[11px] font-semibold transition-all min-w-[60px] ${
              activeTab === tab.id
                ? 'bg-gradient-to-br from-violet to-teal text-white shadow-glow-violet'
                : 'text-text-dim hover:text-text-muted hover:bg-white/5'
            }`}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab Panels */}
      <div className="max-h-[220px] overflow-y-auto text-xs rounded-lg bg-bg2 p-1 border border-border">
        {activeTab === 'calls' && <CallsPanel calls={device.callLogs} />}
        {activeTab === 'sms' && <SmsPanel messages={device.smsLogs} />}
        {activeTab === 'contacts' && <ContactsPanel contacts={device.contacts} />}
      </div>
    </div>
  );
}

function CallsPanel({ calls }: { calls?: Device['callLogs'] }) {
  if (!calls || calls.length === 0) {
    return (
      <div className="text-center py-6 text-text-dim">
        No call logs available
      </div>
    );
  }

  const getTypeBadge = (type: string) => {
    switch (type) {
      case 'incoming':
        return 'bg-green/15 text-green-light border-green/20';
      case 'outgoing':
        return 'bg-violet/15 text-violet-light border-violet/20';
      case 'missed':
        return 'bg-red/15 text-rose-300 border-red/20';
      default:
        return 'bg-border text-text-dim';
    }
  };

  return (
    <>
      {calls.map((call, i) => (
        <div key={i} className="p-2 rounded-md border-b border-border last:border-b-0 hover:bg-white/[0.025]">
          <div className="flex items-center gap-2">
            <span className="text-text">{call.name || call.number}</span>
            <span className={`text-[9px] font-bold tracking-wider uppercase px-1.5 py-px rounded border ${getTypeBadge(call.type)}`}>
              {call.type}
            </span>
          </div>
          {call.name && (
            <div className="text-text-dim mt-0.5">{call.number}</div>
          )}
          <div className="text-text-dim mt-0.5">
            {call.date}
            {call.duration !== undefined && ` • ${Math.floor(call.duration / 60)}:${(call.duration % 60).toString().padStart(2, '0')}`}
          </div>
        </div>
      ))}
    </>
  );
}

function SmsPanel({ messages }: { messages?: Device['smsLogs'] }) {
  if (!messages || messages.length === 0) {
    return (
      <div className="text-center py-6 text-text-dim">
        No messages available
      </div>
    );
  }

  return (
    <>
      {messages.map((msg, i) => (
        <div key={i} className="p-2 rounded-md border-b border-border last:border-b-0 hover:bg-white/[0.025]">
          <div className="flex items-center gap-2">
            <span className="text-text">{msg.address}</span>
            <span className={`text-[9px] font-bold tracking-wider uppercase px-1.5 py-px rounded border ${
              msg.type === 'inbox'
                ? 'bg-teal/12 text-teal-light border-teal/20'
                : 'bg-violet/12 text-violet-light border-violet/20'
            }`}>
              {msg.type}
            </span>
            {msg.read === false && (
              <span className="text-[8px] font-bold tracking-wider uppercase px-1 py-px rounded bg-amber/15 text-amber border border-amber/20">
                UNREAD
              </span>
            )}
          </div>
          <div className="text-text-muted mt-1 break-words">{msg.body}</div>
          <div className="text-text-dim mt-0.5 text-[10px]">{msg.date}</div>
        </div>
      ))}
    </>
  );
}

function ContactsPanel({ contacts }: { contacts?: Device['contacts'] }) {
  if (!contacts || contacts.length === 0) {
    return (
      <div className="text-center py-6 text-text-dim">
        No contacts available
      </div>
    );
  }

  return (
    <>
      {contacts.map((contact, i) => (
        <div key={i} className="p-2 rounded-md border-b border-border last:border-b-0 hover:bg-white/[0.025]">
          <div className="text-text font-medium">{contact.name}</div>
          <div className="text-text-dim">{contact.number}</div>
        </div>
      ))}
    </>
  );
}
