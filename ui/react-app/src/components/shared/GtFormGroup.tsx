import type { ReactNode } from 'react'
import { Label } from '../ui/label'

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
      className="grid grid-cols-3 gap-4 items-start py-2"
      data-gt-invalid={invalidReason ?? undefined}
    >
      <Label htmlFor={htmlFor} className="pt-2 text-right text-gray-700">
        {label}
      </Label>
      <div className="col-span-2">
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
          <p className="mt-1 text-xs text-gray-500">{helpText}</p>
        )}
      </div>
    </div>
  )
}
