import { useLocation } from 'react-router-dom'
import { ExternalLink } from 'lucide-react'
import { toClassicUrl } from '../../lib/api'

/**
 * Fallback component for routes not yet implemented in the modern UI.
 * Shows a message with a link to the equivalent page in the classic UI.
 */
export function NotYetAvailable() {
  const location = useLocation()

  const classicUrl = toClassicUrl(location.pathname, location.search)

  return (
    <div className="flex flex-col items-center justify-center py-20 text-center">
      <div className="rounded-lg border bg-white p-8 shadow-sm max-w-md">
        <h2 className="text-lg font-semibold text-gray-900 mb-2">
          Page not yet available
        </h2>
        <p className="text-sm text-gray-600 mb-4">
          This page has not been implemented in the modern UI yet.
        </p>
        <a
          href={classicUrl}
          target="_self"
          className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors no-underline"
        >
          Open in classic UI
          <ExternalLink className="h-4 w-4" />
        </a>
      </div>
    </div>
  )
}
