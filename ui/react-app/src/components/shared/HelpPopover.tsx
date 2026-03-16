import { useEffect, useRef, useState, type ReactNode } from 'react'
import { HelpCircle } from 'lucide-react'
import { cn } from '../../lib/utils'

export interface HelpPopoverProps {
  title?: string
  children: ReactNode
  className?: string
  buttonAriaLabel?: string
  align?: 'left' | 'right'
}

/**
 * Click-to-toggle "?" help popover matching Classic Glowroot's template/help/*.html popovers.
 */
export function HelpPopover({
  title,
  children,
  className,
  buttonAriaLabel,
  align = 'left',
}: HelpPopoverProps) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return

    function handleMouseDown(event: MouseEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false)
      }
    }

    document.addEventListener('mousedown', handleMouseDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('mousedown', handleMouseDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open])

  return (
    <div ref={containerRef} className={cn('relative inline-flex items-center', className)}>
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        aria-label={buttonAriaLabel ?? title ?? 'Help'}
        title={title ?? 'Help'}
        className="inline-flex h-5 w-5 items-center justify-center rounded-full text-gray-500 hover:bg-gray-100 hover:text-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500"
      >
        <HelpCircle className="h-4 w-4" />
      </button>
      {open && (
        <div
          role="dialog"
          aria-label={title ?? 'Help'}
          className={cn(
            'absolute top-full z-50 mt-1.5 w-80 rounded-md border border-gray-200 bg-white p-3 text-left text-xs text-gray-700 shadow-lg',
            align === 'right' ? 'right-0' : 'left-0',
          )}
        >
          {title && (
            <div className="mb-1.5 font-semibold text-gray-900">{title}</div>
          )}
          <div className="space-y-1.5 leading-relaxed">{children}</div>
        </div>
      )}
    </div>
  )
}
