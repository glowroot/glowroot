import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ChevronLeft } from 'lucide-react'
import { cn } from '../../lib/utils'

export interface SettingsBackLink {
  to: string
  /** Parent list name, e.g. "Alerts" */
  label: string
}

interface SettingsPageProps {
  title: string
  description?: ReactNode
  /** Parent list navigation (replaces old "Return to list" text links) */
  back?: SettingsBackLink
  children: ReactNode
  /** Widen content (lists/tables); default max-w-4xl for forms */
  wide?: boolean
  className?: string
}

export function SettingsPage({
  title,
  description,
  back,
  children,
  wide = false,
  className,
}: SettingsPageProps) {
  return (
    <div className={cn(wide ? 'max-w-6xl' : 'max-w-4xl', className)}>
      <header className="mb-6">
        {back && (
          <Link
            to={back.to}
            className="mb-2 inline-flex items-center gap-0.5 text-sm text-gray-500 no-underline hover:text-gray-800"
          >
            <ChevronLeft className="h-4 w-4 shrink-0" aria-hidden="true" />
            <span>{back.label}</span>
          </Link>
        )}
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
