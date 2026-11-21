import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet } from '../../lib/api'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface PluginSummary {
  id: string
  name: string
  hasConfig: boolean
}

export function ConfigPluginListPage() {
  const { agentId } = useAgent()
  const [plugins, setPlugins] = useState<PluginSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      const resp = await apiGet<PluginSummary[]>('/backend/config/plugins', {
        'agent-id': agentId,
      })
      setPlugins(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentId])

  useEffect(() => { load() }, [load])

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  const configurable = plugins.filter((p) => p.hasConfig)
  const notConfigurable = plugins.filter((p) => !p.hasConfig)

  return (
    <SettingsPage title="Plugins" wide>
      <div className="divide-y divide-gray-100 border-t border-gray-100">
        {configurable.map((plugin) => (
          <Link
            key={plugin.id}
            to={`/modern/config/plugin?agent-id=${encodeURIComponent(agentId!)}&plugin-id=${encodeURIComponent(plugin.id)}`}
            className="flex items-center py-3 text-sm text-gray-900 no-underline hover:bg-gray-50/80 -mx-1 px-1 rounded"
          >
            {plugin.name}
          </Link>
        ))}
        {configurable.length === 0 && (
          <p className="py-3 text-sm text-gray-500">No configurable plugins.</p>
        )}
      </div>
      {notConfigurable.length > 0 && (
        <p className="text-sm text-gray-500">
          Plugins installed with no configuration: {notConfigurable.map((p) => p.name).join(', ')}
        </p>
      )}
    </SettingsPage>
  )
}
