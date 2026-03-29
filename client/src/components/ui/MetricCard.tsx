import type { ReactNode } from 'react'

type MetricCardProps = {
  label: string
  value: string | number
  icon?: ReactNode
  color: 'red' | 'yellow' | 'green' | 'blue'
  className?: string
}

const colorClasses = {
  red: 'bg-red-500 border-red-600',
  yellow: 'bg-yellow-500 border-yellow-600',
  green: 'bg-green-500 border-green-600',
  blue: 'bg-blue-500 border-blue-600',
}

const textColorClasses = {
  red: 'text-white',
  yellow: 'text-gray-900',
  green: 'text-white',
  blue: 'text-white',
}

export function MetricCard({ label, value, icon, color, className = '' }: MetricCardProps) {
  return (
    <div 
      className={`
        rounded-lg border-2 p-3 shadow-card flex items-center justify-between
        ${colorClasses[color]}
        ${className}
      `.trim()}
    >
      <div className="flex-1">
        <div className={`text-2xl font-bold ${textColorClasses[color]}`}>
          {value}
        </div>
        <div className={`mt-0.5 text-xs font-medium uppercase tracking-wide ${textColorClasses[color]} opacity-90`}>
          {label}
        </div>
      </div>
      {icon && (
        <div className={`flex h-12 w-12 items-center justify-center ${textColorClasses[color]} opacity-80`}>
          {icon}
        </div>
      )}
    </div>
  )
}
