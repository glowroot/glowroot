import type { ReactNode } from 'react'
import { Label } from '../ui/label'
import { cn } from '../../lib/utils'

interface GtFormGroupProps {
  label: string
  htmlFor?: string
  helpText?: string
  required?: boolean
  pattern?: RegExp
  value?: string | number | null
  invalidMessage?: string
  invalid?: boolean
  children: ReactNode
  className?: string
  /** Label above control — good inside multi-column grids */
  layout?: 'row' | 'stack'
  /** Tighter padding / narrower default control column */
  compact?: boolean
  /** Extra classes on the control wrapper (width caps, etc.) */
  controlClassName?: string
}

function matchesPattern(pattern: RegExp, input: string): boolean {
  if (pattern.global || pattern.sticky) {
    pattern.lastIndex = 0
  }
  return pattern.test(input)
}

export function GtFormGroup({
  label,
  htmlFor,
  helpText,
  required,
  pattern,
  value,
  invalidMessage,
  invalid,
  children,
  className,
  layout = 'row',
  compact = false,
  controlClassName,
}: GtFormGroupProps) {
  let invalidReason: 'required' | 'other' | null = null
  if (value !== undefined) {
    const strValue = value === null ? '' : String(value)
    if (required && strValue.trim() === '') {
      invalidReason = 'required'
    } else if (invalid) {
      invalidReason = 'other'
    } else if (pattern && strValue !== '' && !matchesPattern(pattern, strValue)) {
      invalidReason = 'other'
    }
  } else if (invalid) {
    invalidReason = 'other'
  }

  const stacked = layout === 'stack'

  return (
    <div
      className={cn(
        'flex',
        stacked
          ? cn('flex-col gap-1', compact ? 'py-1' : 'py-1.5')
          : cn(
              'flex-col sm:flex-row sm:items-start',
              compact ? 'gap-1 py-1 sm:gap-3' : 'gap-1.5 py-1.5 sm:gap-4'
            ),
        className
      )}
      data-gt-invalid={invalidReason ?? undefined}
    >
      <Label
        htmlFor={htmlFor}
        className={cn(
          'shrink-0 text-sm text-gray-700',
          stacked
            ? 'pt-0 text-left'
            : cn('pt-1.5 sm:text-right', compact ? 'sm:w-40' : 'sm:w-52')
        )}
      >
        {label}
      </Label>
      <div
        className={cn(
          'min-w-0',
          stacked ? 'w-full' : 'flex-1',
          stacked ? undefined : compact ? 'max-w-[28em]' : 'max-w-[40em]',
          controlClassName
        )}
      >
        {children}
        {required && (
          <div className="gt-invalid-required-feedback mt-1 text-xs text-red-600">
            This is required.
          </div>
        )}
        {(pattern || invalidMessage || invalid !== undefined) && (
          <div className="gt-invalid-other-feedback mt-1 text-xs text-red-600">
            {invalidMessage ?? 'This is invalid.'}
          </div>
        )}
        {helpText && (
          <p
            className={cn(
              'text-gray-500 leading-snug',
              compact ? 'mt-0.5 text-[11px]' : 'mt-1 text-xs leading-relaxed'
            )}
          >
            {helpText}
          </p>
        )}
      </div>
    </div>
  )
}
