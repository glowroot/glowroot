import { forwardRef, type SelectHTMLAttributes } from 'react'
import { cn } from '../../lib/utils'

const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  ({ className, children, ...props }, ref) => {
    return (
      <select
        className={cn(
          'flex h-8 w-full rounded-md border border-gray-300 bg-white px-2.5 py-1 text-sm transition-colors',
          'focus-visible:outline-none focus-visible:border-[#417998] focus-visible:ring-1 focus-visible:ring-[#417998]',
          'disabled:cursor-not-allowed disabled:bg-gray-50 disabled:opacity-60',
          className
        )}
        ref={ref}
        {...props}
      >
        {children}
      </select>
    )
  }
)
Select.displayName = 'Select'

export { Select }
