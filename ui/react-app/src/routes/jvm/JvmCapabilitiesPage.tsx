import { useCallback, useEffect, useState } from 'react'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet } from '../../lib/api'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface Capability {
  available: boolean
  reason?: string
}

interface CapabilitiesData {
  agentNotConnected?: boolean
  threadCpuTime: Capability
  threadContentionTime: Capability
  threadAllocatedBytes: Capability
}

function CapabilityRow({ label, cap }: { label: string; cap: Capability }) {
  return (
    <div className="grid grid-cols-3 gap-4 py-2 border-b border-gray-100 last:border-b-0">
      <div className="text-sm font-medium text-gray-700">{label}</div>
      <div className="col-span-2 text-sm">
        <span className={cap.available ? 'text-green-700' : 'text-gray-500'}>
          {cap.available ? 'On' : 'Off'}
        </span>
        {!cap.available && cap.reason && (
          <p className="text-xs text-gray-500 mt-0.5">{cap.reason}</p>
        )}
      </div>
    </div>
  )
}

export function JvmCapabilitiesPage() {
  const { agentId } = useAgent()
  const [data, setData] = useState<CapabilitiesData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      const resp = await apiGet<CapabilitiesData>('/backend/jvm/capabilities', {
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
    <div className="rounded-lg border bg-white p-4">
      <CapabilityRow label="Thread CPU time" cap={data.threadCpuTime} />
      <CapabilityRow label="Thread contention time" cap={data.threadContentionTime} />
      <CapabilityRow label="Thread allocated bytes" cap={data.threadAllocatedBytes} />
    </div>
  )
}
