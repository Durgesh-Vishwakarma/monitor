import type { PropsWithChildren, ReactNode } from 'react'

type PanelProps = PropsWithChildren<{
  title: string
  className?: string
  action?: ReactNode
  subtitle?: string
}>

export function Panel({ title, subtitle, action, className = '', children }: PanelProps) {
  return (
    <article 
      className={`
        rounded-lg border border-dark-700 
        bg-dark-800 
        p-4 shadow-card
        ${className}
      `.trim()}
    >
      <div className="mb-3 flex items-start justify-between">
        <div>
          <h2 className="text-sm font-semibold text-white">
            {title}
          </h2>
          {subtitle && (
            <p className="mt-0.5 text-xs text-slate-400">{subtitle}</p>
          )}
        </div>
        {action && <div>{action}</div>}
      </div>
      {children}
    </article>
  )
}
