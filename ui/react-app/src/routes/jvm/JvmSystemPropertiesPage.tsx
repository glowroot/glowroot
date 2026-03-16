import { useCallback, useEffect, useState } from 'react'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet } from '../../lib/api'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface SystemProperty {
  name: string
  value: string | string[]
}

interface SystemPropertiesData {
  agentNotConnected?: boolean
  agentUnsupportedOperation?: string | false
  properties: SystemProperty[]
}

function renderScalarWithWhitespace(value: string) {
  const parts: Array<string | { key: number; label: string }> = []
  let buf = ''
  for (let i = 0; i < value.length; i++) {
    const ch = value[i]
    if (ch === '\n' || ch === '\r' || ch === '\t') {
      if (buf) {
        parts.push(buf)
        buf = ''
      }
      parts.push({
        key: i,
        label: ch === '\n' ? '\\n' : ch === '\r' ? '\\r' : '\\t',
      })
    } else {
      buf += ch
    }
  }
  if (buf) parts.push(buf)
  return parts.map((p) =>
    typeof p === 'string' ? (
      p
    ) : (
      <em key={p.key} className="text-gray-500">
        {p.label}
      </em>
    )
  )
}

export function JvmSystemPropertiesPage() {
  const { agentId } = useAgent()
  const [data, setData] = useState<SystemPropertiesData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      const resp = await apiGet<SystemPropertiesData>('/backend/jvm/system-properties', {
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

  if (data.agentUnsupportedOperation) {
    return (
      <div className="rounded-md bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-700">
        This feature is only supported by Glowroot agent 0.9.2 and later
        (currently reporting agent version is {data.agentUnsupportedOperation})
      </div>
    )
  }

  const sorted = [...data.properties].sort((a, b) => a.name.localeCompare(b.name))

  return (
    <div className="rounded-lg border bg-white overflow-hidden">
      <table className="min-w-full divide-y divide-gray-200 text-sm">
        <thead className="bg-gray-50">
          <tr>
            <th className="px-4 py-2 text-left font-medium text-gray-700">Property</th>
            <th className="px-4 py-2 text-left font-medium text-gray-700">Value</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {sorted.map((prop) => (
            <tr key={prop.name}>
              <td className="px-4 py-1.5 font-mono text-xs text-gray-900 whitespace-nowrap align-top">
                {prop.name}
              </td>
              <td className="px-4 py-1.5 text-gray-700 break-words">
                {Array.isArray(prop.value) ? (
                  <div style={{ marginBottom: prop.value.length > 1 ? '5px' : '0' }}>
                    {prop.value.map((val, idx) => (
                      <div key={idx} style={{ marginLeft: '40px', textIndent: '-40px' }}>
                        {val}
                      </div>
                    ))}
                  </div>
                ) : (
                  renderScalarWithWhitespace(String(prop.value))
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
