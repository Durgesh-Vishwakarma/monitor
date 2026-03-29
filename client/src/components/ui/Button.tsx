import type { ButtonHTMLAttributes, PropsWithChildren } from 'react'

type ButtonVariant = 'primary' | 'secondary' | 'success' | 'warning' | 'danger' | 'ghost'
type ButtonSize = 'sm' | 'md' | 'lg'

type ButtonProps = PropsWithChildren<{
  variant?: ButtonVariant
  size?: ButtonSize
  className?: string
}> & ButtonHTMLAttributes<HTMLButtonElement>

const variantClasses: Record<ButtonVariant, string> = {
  primary: 'bg-blue-500 text-white hover:bg-blue-600 border border-blue-600',
  success: 'bg-green-500 text-white hover:bg-green-600 border border-green-600',
  warning: 'bg-yellow-500 text-gray-900 hover:bg-yellow-600 border border-yellow-600',
  danger: 'bg-red-500 text-white hover:bg-red-600 border border-red-600',
  secondary: 'bg-dark-700 text-slate-200 hover:bg-dark-600 border border-dark-600',
  ghost: 'bg-transparent text-slate-300 hover:bg-dark-700',
}

const sizeClasses: Record<ButtonSize, string> = {
  sm: 'px-2.5 py-1 text-xs',
  md: 'px-3 py-1.5 text-sm',
  lg: 'px-4 py-2 text-sm',
}

export function Button({ 
  variant = 'primary', 
  size = 'md', 
  className = '', 
  children,
  disabled,
  ...props 
}: ButtonProps) {
  return (
    <button
      className={`
        inline-flex items-center justify-center gap-1.5 rounded font-medium
        transition-colors duration-150 disabled:opacity-50 disabled:cursor-not-allowed
        ${variantClasses[variant]}
        ${sizeClasses[size]}
        ${className}
      `.trim()}
      disabled={disabled}
      {...props}
    >
      {children}
    </button>
  )
}
