import { useCallback, useEffect, useState } from 'react'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet, apiPost } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface GeneralConfig {
  display: string
  defaultDisplay?: string
  version: string
}

export function ConfigGeneralPage() {
  const { agentRollupId, agentRollup, isRollup, refreshAgentRollup } = useAgent()
  const [config, setConfig] = useState<GeneralConfig | null>(null)
  const [originalConfig, setOriginalConfig] = useState<GeneralConfig | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.general ?? false

  const load = useCallback(async () => {
    if (agentRollupId === null) return
    try {
      const resp = await apiGet<GeneralConfig>('/backend/config/general', {
        'agent-rollup-id': agentRollupId,
      })
      setConfig({ ...resp })
      setOriginalConfig({ ...resp })
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentRollupId])

  useEffect(() => { load() }, [load])

  const hasChanges = config && originalConfig
    ? JSON.stringify(config) !== JSON.stringify(originalConfig)
    : false

  async function handleSave() {
    if (agentRollupId === null) return
    const resp = await apiPost<GeneralConfig>(
      '/backend/config/general?agent-rollup-id=' + encodeURIComponent(agentRollupId),
      config
    )
    setConfig({ ...resp })
    setOriginalConfig({ ...resp })
    await refreshAgentRollup()
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!config) return null

  return (
    <div className="rounded-lg border bg-white p-6">
      <h2 className="text-lg font-semibold text-gray-900 mb-4">General</h2>
      <GtFormGroup
        label={isRollup ? 'Agent rollup ID' : 'Agent ID'}
        helpText={`Cannot be changed, used for storing and querying the agent${isRollup ? ' rollup' : ''}'s data.`}
      >
        <Input value={agentRollupId ?? ''} disabled />
      </GtFormGroup>
      <GtFormGroup
        label="Display name"
        helpText={`Display name for this agent${isRollup ? ' rollup' : ''}.`}
      >
        <Input
          value={config.display}
          onChange={(e) => setConfig({ ...config, display: e.target.value })}
          placeholder={config.defaultDisplay}
          disabled={!canEdit}
        />
      </GtFormGroup>
      {isRollup && (
        <div className="mb-4 text-xs text-gray-500 italic sm:ml-[26%]">
          NOTE: the agent rollup configuration (synthetic monitors, alerts, UI and advanced) applies directly to the agent rollup data. This configuration has no affect on individual agents under the rollup.
        </div>
      )}
      {canEdit && (
        <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
      )}
    </div>
  )
}
