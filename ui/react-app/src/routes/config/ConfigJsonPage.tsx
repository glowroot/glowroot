import { useCallback, useEffect, useState } from 'react'
import { useAgent } from '../../contexts/AgentContext'
import { apiGetText, apiPostText } from '../../lib/api'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

export function ConfigJsonPage() {
  const { agentId, agentRollup } = useAgent()
  const [configJson, setConfigJson] = useState('')
  const [originalConfigJson, setOriginalConfigJson] = useState('')
  const [configVersion, setConfigVersion] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.all ?? false

  function processRawJson(raw: string) {
    // Strip version from display JSON
    const stripped = raw.replace(/,\s*"version":\s*"[0-9a-f]{40}"/, '')
    setConfigJson(stripped)
    setOriginalConfigJson(stripped)
    setConfigVersion(JSON.parse(raw).version)
  }

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      const raw = await apiGetText('/backend/config/json', { 'agent-id': agentId })
      processRawJson(raw)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentId])

  useEffect(() => { load() }, [load])

  const hasChanges = configJson !== originalConfigJson

  async function handleSave() {
    if (agentId === null) return
    let postData: Record<string, unknown>
    try {
      postData = JSON.parse(configJson)
    } catch (e) {
      throw new Error('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)))
    }
    // Put back version
    postData.version = configVersion
    const raw = await apiPostText('/backend/config/json', postData, { 'agent-id': agentId })
    processRawJson(raw)
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  const lines = Math.max((configJson.match(/\n/g) || []).length + 1, 10)

  return (
    <div className="rounded-lg border bg-white p-6">
      <h2 className="text-lg font-semibold text-gray-900 mb-4">config.json</h2>
      <textarea
        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm font-mono"
        rows={lines}
        value={configJson}
        onChange={(e) => setConfigJson(e.target.value)}
        disabled={!canEdit}
      />
      {canEdit && (
        <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
      )}
    </div>
  )
}
