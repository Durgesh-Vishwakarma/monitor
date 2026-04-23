const variantClasses = {
  default: 'bg-dark-700 text-slate-200 border-dark-600',
  success: 'bg-green-900/60 text-green-300 border-green-700',
  warning: 'bg-yellow-900/60 text-yellow-300 border-yellow-700',
  error: 'bg-red-900/60 text-red-300 border-red-700',
  info: 'bg-blue-900/60 text-blue-300 border-blue-700'
};
export function Badge({
  variant = 'default',
  className = '',
  pulse = false,
  children
}) {
  return <span className={`
        inline-flex items-center gap-1.5 rounded border px-2 py-0.5 
        text-xs font-medium
        ${variantClasses[variant]}
        ${pulse ? 'animate-pulse' : ''}
        ${className}
      `.trim()}>
      {pulse && <span className="h-1.5 w-1.5 rounded-full bg-current" />}
      {children}
    </span>;
}