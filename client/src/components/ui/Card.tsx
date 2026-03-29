import type { PropsWithChildren } from 'react'

type CardProps = PropsWithChildren<{
  className?: string
  hover?: boolean
  glow?: boolean
}>

export function Card({ className = '', hover = false, glow = false, children }: CardProps) {
  return (
    <div 
      className={`
        card p-6 
        ${hover ? 'card-hover cursor-pointer' : ''}
        ${glow ? 'shadow-glow' : 'shadow-lg'}
        ${className}
      `.trim()}
    >
      {children}
    </div>
  )
}

type CardHeaderProps = PropsWithChildren<{
  className?: string
}>

export function CardHeader({ className = '', children }: CardHeaderProps) {
  return (
    <div className={`mb-4 ${className}`.trim()}>
      {children}
    </div>
  )
}

type CardTitleProps = PropsWithChildren<{
  className?: string
}>

export function CardTitle({ className = '', children }: CardTitleProps) {
  return (
    <h3 className={`text-lg font-semibold text-slate-50 ${className}`.trim()}>
      {children}
    </h3>
  )
}

type CardDescriptionProps = PropsWithChildren<{
  className?: string
}>

export function CardDescription({ className = '', children }: CardDescriptionProps) {
  return (
    <p className={`text-sm text-slate-400 mt-1 ${className}`.trim()}>
      {children}
    </p>
  )
}

type CardContentProps = PropsWithChildren<{
  className?: string
}>

export function CardContent({ className = '', children }: CardContentProps) {
  return (
    <div className={className}>
      {children}
    </div>
  )
}
