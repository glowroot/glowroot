/*
 * Shared Average / Percentiles / Throughput switcher for response-time pages.
 */

import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { RefreshCw, ZoomOut } from 'lucide-react'
import { HelpPopover } from '../../components/shared/HelpPopover'
import { cn } from '../../lib/utils'

export type ResponseTimeView = 'average' | 'percentiles' | 'throughput'

const VIEWS: Array<{ id: ResponseTimeView; label: string }> = [
  { id: 'average', label: 'Average' },
  { id: 'percentiles', label: 'Percentiles' },
  { id: 'throughput', label: 'Throughput' },
]

interface ResponseTimeSubNavProps {
  active: ResponseTimeView
  basePath: string
  qs: string
  onRefreshActive: () => void
  onZoomOut: () => void
  help?: ReactNode
}

export function ResponseTimeSubNav({
  active,
  basePath,
  qs,
  onRefreshActive,
  onZoomOut,
  help,
}: ResponseTimeSubNavProps) {
  return (
    <div className="flex items-center justify-between gap-4 flex-wrap">
      <div
        role="tablist"
        aria-label="Response time view"
        className="inline-flex items-center rounded-md border border-gray-200 bg-gray-50 p-0.5"
      >
        {VIEWS.map((view) => {
          const isActive = view.id === active
          return (
            <Link
              key={view.id}
              role="tab"
              aria-selected={isActive}
              to={`${basePath}/${view.id}${qs}`}
              onClick={(e) => {
                if (!isActive) return
                e.preventDefault()
                onRefreshActive()
              }}
              className={cn(
                'rounded px-3 py-1.5 text-sm no-underline transition-colors',
                isActive
                  ? 'bg-white font-medium text-gray-900 shadow-sm'
                  : 'text-gray-500 hover:text-gray-800'
              )}
            >
              {view.label}
            </Link>
          )
        })}
      </div>
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={onZoomOut}
          title="Zoom out"
          aria-label="Zoom out"
          className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
        >
          <ZoomOut className="h-4 w-4" />
        </button>
        <button
          type="button"
          onClick={onRefreshActive}
          title="Refresh"
          aria-label="Refresh"
          className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
        >
          <RefreshCw className="h-4 w-4" />
        </button>
        <HelpPopover title="Chart navigation" align="right">
          {help ?? (
            <div className="space-y-1 text-xs">
              <div className="font-semibold">Chart navigation tips</div>
              <ul className="list-disc pl-4 space-y-0.5">
                <li>Drag or double click to zoom in</li>
                <li>Use Ctrl + scroll wheel to zoom in and out</li>
              </ul>
            </div>
          )}
        </HelpPopover>
      </div>
    </div>
  )
}
