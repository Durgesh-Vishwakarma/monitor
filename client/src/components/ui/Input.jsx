export function Input({
  label,
  error,
  className = '',
  ...props
}) {
  return <div className="w-full">
      {label && <label className="mb-2 block text-sm font-medium text-slate-300">
          {label}
        </label>}
      <input className={`
          w-full rounded-lg border border-primary-500/20 
          bg-dark-800/50 px-4 py-2.5 text-sm text-slate-100 
          placeholder:text-slate-500 backdrop-blur-sm
          transition-all duration-200
          focus:border-primary-500/50 focus:outline-none focus:ring-2 focus:ring-primary-500/20
          disabled:cursor-not-allowed disabled:opacity-50
          ${error ? 'border-red-500/50 focus:border-red-500 focus:ring-red-500/20' : ''}
          ${className}
        `.trim()} {...props} />
      {error && <p className="mt-1.5 text-xs text-red-400">{error}</p>}
    </div>;
}