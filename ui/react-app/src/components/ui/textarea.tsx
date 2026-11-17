import { forwardRef, type TextareaHTMLAttributes } from 'react'
import { cn } from '../../lib/utils'

const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...props }, ref) => {
    return (
      <textarea
        className={cn(
          'flex min-h-[60px] w-full rounded-md border border-gray-300 bg-white px-2.5 py-1.5 text-sm transition-colors',
          'placeholder:text-gray-400',
          'focus-visible:outline-none focus-visible:border-[#417998] focus-visible:ring-1 focus-visible:ring-[#417998]',
          'disabled:cursor-not-allowed disabled:bg-gray-50 disabled:opacity-60',
          className
        )}
        ref={ref}
        {...props}
      />
    )
  }
)
Textarea.displayName = 'Textarea'

export { Textarea }
