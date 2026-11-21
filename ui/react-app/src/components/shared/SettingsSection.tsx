import type { ReactNode } from 'react'
import { cn } from '../../lib/utils'

interface SettingsSectionProps {
  title?: string
  description?: ReactNode
  children: ReactNode
  className?: string
  /** First section on a page can omit the top hairline */
  first?: boolean
}

export function SettingsSection({
  title,
  description,
  children,
  className,
  first = false,
}: SettingsSectionProps) {
  return (
    <section
      className={cn(
        !first && 'mt-8 border-t border-gray-200 pt-8',
        className
      )}
    >
      {(title || description) && (
        <div className="mb-4">
          {title && (
            <h3 className="text-sm font-semibold uppercase tracking-wide text-gray-600">
              {title}
            </h3>
          )}
          {description && (
            <div className="mt-1 text-sm text-gray-500 leading-relaxed">{description}</div>
          )}
        </div>
      )}
      <div>{children}</div>
    </section>
  )
}
