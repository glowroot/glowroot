import type { ReactNode } from 'react'
import { cn } from '../../lib/utils'

interface SettingsPageProps {
  title: string
  description?: ReactNode
  children: ReactNode
  /** Widen content (lists/tables); default max-w-4xl for forms */
  wide?: boolean
  className?: string
}

export function SettingsPage({
  title,
  description,
  children,
  wide = false,
  className,
}: SettingsPageProps) {
  return (
    <div className={cn(wide ? 'max-w-6xl' : 'max-w-4xl', className)}>
      <header className="mb-6">
        <h2 className="text-xl font-semibold text-gray-900 tracking-tight">{title}</h2>
        {description && (
          <div className="mt-1.5 text-sm text-gray-500 leading-relaxed">{description}</div>
        )}
      </header>
      {/* No space-y here: form fields stay dense; SettingsSection supplies section gaps */}
      <div>{children}</div>
    </div>
  )
}
