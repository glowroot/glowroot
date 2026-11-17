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

  return (
    <div
      className={cn('flex flex-col gap-1.5 py-1.5 sm:flex-row sm:gap-4 sm:items-start', className)}
      data-gt-invalid={invalidReason ?? undefined}
    >
      <Label
        htmlFor={htmlFor}
        className="shrink-0 pt-1.5 text-sm text-gray-700 sm:w-52 sm:text-right"
      >
        {label}
      </Label>
      <div className="min-w-0 flex-1 max-w-[40em]">
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
          <p className="mt-1 text-xs text-gray-500 leading-relaxed">{helpText}</p>
        )}
      </div>
    </div>
  )
}
