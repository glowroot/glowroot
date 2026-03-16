import { useCallback, useEffect, useState } from 'react'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet, apiPost } from '../../lib/api'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface GcCheckData {
  agentNotConnected?: boolean
  explicitGcDisabled?: boolean
}

export function JvmForceGcPage() {
  const { agentId } = useAgent()
  const [data, setData] = useState<GcCheckData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<string | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      const resp = await apiGet<GcCheckData>('/backend/jvm/explicit-gc-disabled', {
        'agent-id': agentId,
      })
      setData(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentId])

  useEffect(() => { load() }, [load])

  async function handleForceGc() {
    if (agentId === null) return
    setRunning(true)
    setResult(null)
    setActionError(null)
    try {
      await apiPost('/backend/jvm/force-gc?agent-id=' + encodeURIComponent(agentId))
      setResult('Success')
    } catch (e) {
      setActionError(e)
    } finally {
      setRunning(false)
    }
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!data) return null

  if (data.agentNotConnected) {
    return (
      <div className="rounded-md bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
        This feature is only available when the agent is running and connected
      </div>
    )
  }

  return (
    <div className="rounded-lg border bg-white p-6">
      {data.explicitGcDisabled ? (
        <div className="rounded-md bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
          This feature is not available because the JVM is running with -XX:+DisableExplicitGC
        </div>
      ) : (
        <div className="space-y-4">
          <div className="flex items-center gap-4">
            <Button onClick={handleForceGc} loading={running}>
              Force GC
            </Button>
            {result && (
              <span className="text-sm text-green-600">{result}</span>
            )}
          </div>
          {actionError != null && <HttpError error={actionError} />}
        </div>
      )}
    </div>
  )
}
