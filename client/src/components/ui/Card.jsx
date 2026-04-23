export function Card({
  className = '',
  hover = false,
  glow = false,
  children
}) {
  return <div className={`
        card p-6 
        ${hover ? 'card-hover cursor-pointer' : ''}
        ${glow ? 'shadow-glow' : 'shadow-lg'}
        ${className}
      `.trim()}>
      {children}
    </div>;
}
export function CardHeader({
  className = '',
  children
}) {
  return <div className={`mb-4 ${className}`.trim()}>
      {children}
    </div>;
}
export function CardTitle({
  className = '',
  children
}) {
  return <h3 className={`text-lg font-semibold text-slate-50 ${className}`.trim()}>
      {children}
    </h3>;
}
export function CardDescription({
  className = '',
  children
}) {
  return <p className={`text-sm text-slate-400 mt-1 ${className}`.trim()}>
      {children}
    </p>;
}
export function CardContent({
  className = '',
  children
}) {
  return <div className={className}>
      {children}
    </div>;
}