export function Select({
  label,
  error,
  options,
  className = '',
  ...props
}) {
  return <div className="w-full">
      {label && <label className="mb-2 block text-sm font-medium text-slate-300">
          {label}
        </label>}
      <select className={`
          w-full rounded border border-dark-600 
          bg-dark-700 px-3 py-2 text-sm text-white 
          transition-colors duration-150
          focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500
          disabled:cursor-not-allowed disabled:opacity-50
          ${error ? 'border-red-500 focus:border-red-500 focus:ring-red-500' : ''}
          ${className}
        `.trim()} {...props}>
        {options.map(option => <option key={option.value} value={option.value}>
            {option.label}
          </option>)}
      </select>
      {error && <p className="mt-1.5 text-xs text-red-400">{error}</p>}
    </div>;
}