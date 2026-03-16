import { useCallback, useEffect, useState } from 'react'
import { useAgent } from '../../contexts/AgentContext'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet } from '../../lib/api'
import { formatBytes, formatDuration } from '../../lib/formatting'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface EnvironmentData {
  agentNotConnected?: boolean
  host?: {
    hostname?: string
    availableProcessors?: number
    totalPhysicalMemoryBytes?: number
    osName?: string
    osVersion?: string
    currentTime?: number
  }
  process?: {
    processId?: number
    startTime?: number
  }
  java?: {
    version?: string
    vm?: string
    args?: string[]
    glowrootAgentVersion?: string
  }
}

function argFirstPart(arg: string): string {
  if (arg.length > 1 && arg[0] === '-') {
    return arg.substring(0, 2)
  }
  return ''
}

function argSecondPart(arg: string): string {
  if (arg.length > 1 && arg[0] === '-') {
    return arg.substring(2)
  }
  return arg
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-4 gap-4 py-1.5">
      <div className="text-right text-sm font-semibold text-gray-700">{label}</div>
      <div className="col-span-3 text-sm text-gray-900">{children}</div>
    </div>
  )
}

export function JvmEnvironmentPage() {
  const { agentId, agentRollup } = useAgent()
  const { layout } = useLayout()
  const [data, setData] = useState<EnvironmentData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      const resp = await apiGet<EnvironmentData>('/backend/jvm/environment', {
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

  if (!data.host && !data.process && !data.java) {
    return (
      <div className="space-y-4">
        {data.agentNotConnected && (
          <div className="rounded-md bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
            The agent is not currently connected. The values below were reported at last JVM start.
          </div>
        )}
        {layout.offlineViewer && (
          <div className="rounded-md bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-700">
            The values below were reported at last JVM start.
          </div>
        )}
      </div>
    )
  }

  const ver = agentRollup?.glowrootVersion || data.java?.glowrootAgentVersion || ''
  const supportsHostCurrentTime =
    !ver ||
    (!ver.startsWith('0.9.') &&
      !ver.startsWith('0.10.') &&
      !ver.startsWith('0.11.') &&
      !ver.startsWith('0.12.'))

  const startTime = data.process?.startTime
  const uptime =
    data.host?.currentTime != null && startTime != null
      ? data.host.currentTime - startTime
      : !data.agentNotConnected && !layout.offlineViewer && startTime != null
        ? Date.now() - startTime
        : undefined

  return (
    <div className="space-y-6">
      {data.agentNotConnected && (
        <div className="rounded-md bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
          The agent is not currently connected. The values below were reported at last JVM start.
        </div>
      )}
      {layout.offlineViewer && (
        <div className="rounded-md bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-700">
          The values below were reported at last JVM start.
        </div>
      )}

      <fieldset className="rounded-lg border bg-white p-4">
        <legend className="text-sm font-semibold text-gray-700 px-2">Host</legend>
        <Row label="Host name">{data.host?.hostname}</Row>
        <Row label="Available processors">{data.host?.availableProcessors}</Row>
        <Row label="Total physical memory">
          {data.host?.totalPhysicalMemoryBytes != null
            ? formatBytes(data.host.totalPhysicalMemoryBytes)
            : '-'}
        </Row>
        <Row label="OS name">{data.host?.osName}</Row>
        <Row label="OS version">{data.host?.osVersion}</Row>
        {supportsHostCurrentTime && (
          <Row label="Current time">
            {data.agentNotConnected || layout.offlineViewer || data.host?.currentTime == null
              ? '-'
              : new Date(data.host.currentTime).toLocaleString()}
          </Row>
        )}
      </fieldset>

      <fieldset className="rounded-lg border bg-white p-4">
        <legend className="text-sm font-semibold text-gray-700 px-2">Process</legend>
        <Row label="Process ID">
          {data.process?.processId === undefined ? 'N/A' : data.process.processId}
        </Row>
        <Row label="Start time">
          {startTime != null ? new Date(startTime).toLocaleString() : '-'}
        </Row>
        <Row label="Uptime">
          {uptime !== undefined ? formatDuration(uptime) : '-'}
        </Row>
      </fieldset>

      <fieldset className="rounded-lg border bg-white p-4">
        <legend className="text-sm font-semibold text-gray-700 px-2">Java</legend>
        <Row label="Version">{data.java?.version}</Row>
        <Row label="VM">{data.java?.vm}</Row>
        <Row label="Args">
          <div className="space-y-0.5">
            {(data.java?.args || []).map((arg, i) => (
              <div key={i} className="break-words">
                <span className="whitespace-nowrap">{argFirstPart(arg)}</span>
                {argSecondPart(arg)}
              </div>
            ))}
          </div>
        </Row>
      </fieldset>

      <fieldset className="rounded-lg border bg-white p-4">
        <legend className="text-sm font-semibold text-gray-700 px-2">Glowroot agent</legend>
        <Row label="Version">{data.java?.glowrootAgentVersion}</Row>
      </fieldset>
    </div>
  )
}
