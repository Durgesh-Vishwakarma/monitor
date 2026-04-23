export function StatCard({
  label,
  value,
  icon,
  trend,
  className = ''
}) {
  return <div className={`
        rounded border border-dark-700 
        bg-dark-800 
        p-3 transition-colors duration-150
        hover:border-dark-600
        ${className}
      `.trim()}>
      <div className="flex items-start justify-between">
        <div className="flex-1">
          <p className="text-xs font-medium text-slate-400">
            {label}
          </p>
          <p className="mt-1 text-xl font-semibold text-white">
            {value}
          </p>
          {trend && <p className={`mt-0.5 text-xs font-medium ${trend.isPositive ? 'text-emerald-400' : 'text-red-400'}`}>
              {trend.isPositive ? '↑' : '↓'} {Math.abs(trend.value)}%
            </p>}
        </div>
        {icon && <div className="flex h-8 w-8 items-center justify-center rounded bg-slate-700 text-slate-300">
            {icon}
          </div>}
      </div>
    </div>;
}