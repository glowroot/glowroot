import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet } from '../../lib/api'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface SyntheticMonitorSummary {
  id: string
  display: string
}

export function ConfigSyntheticMonitorListPage() {
  const { agentRollupId, agentRollup, agentQueryString } = useAgent()
  const [monitors, setMonitors] = useState<SyntheticMonitorSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.syntheticMonitors ?? false

  const load = useCallback(async () => {
    if (agentRollupId === null) return
    try {
      const resp = await apiGet<SyntheticMonitorSummary[]>('/backend/config/synthetic-monitors', {
        'agent-rollup-id': agentRollupId,
      })
      setMonitors(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentRollupId])

  useEffect(() => { load() }, [load])

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  const qs = agentQueryString()

  return (
    <SettingsPage title="Synthetic monitors" wide>
      <div className="divide-y divide-gray-100 border-t border-gray-100">
        {monitors.map((monitor) => (
          <Link
            key={monitor.id}
            to={`/modern/config/synthetic-monitor${qs}${qs ? '&' : '?'}id=${encodeURIComponent(monitor.id)}`}
            className="flex items-center py-3 text-sm text-gray-900 no-underline hover:bg-gray-50/80 -mx-1 px-1 rounded"
          >
            {monitor.display}
          </Link>
        ))}
        {monitors.length === 0 && (
          <p className="py-3 text-sm text-gray-500">No synthetic monitors configured.</p>
        )}
      </div>
      {canEdit && (
        <div className="mt-4">
          <Link
            to={`/modern/config/synthetic-monitor${qs}${qs ? '&' : '?'}new`}
            className="inline-flex items-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 no-underline"
          >
            Add new
          </Link>
        </div>
      )}
    </SettingsPage>
  )
}
