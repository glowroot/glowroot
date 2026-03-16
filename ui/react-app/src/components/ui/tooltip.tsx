import { useState, type ReactNode } from 'react'
import { cn } from '../../lib/utils'

interface TooltipProps {
  content: ReactNode
  children: ReactNode
  className?: string
}

export function Tooltip({ content, children, className }: TooltipProps) {
  const [show, setShow] = useState(false)

  return (
    <div
      className="relative inline-block"
      onMouseEnter={() => setShow(true)}
      onMouseLeave={() => setShow(false)}
    >
      {children}
      {show && (
        <div
          className={cn(
            'absolute bottom-full left-1/2 z-50 mb-2 -translate-x-1/2 rounded-md',
            'bg-gray-900 px-3 py-1.5 text-xs text-white shadow-md',
            'whitespace-nowrap',
            className
          )}
        >
          {content}
        </div>
      )}
    </div>
  )
}
