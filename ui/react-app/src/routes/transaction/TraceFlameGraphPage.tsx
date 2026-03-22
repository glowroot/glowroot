/*
 * TraceFlameGraphPage — Standalone D3 flame graph visualization for a single trace's thread profile.
 *
 * Backend: GET /backend/transaction/traces/flame-graph
 */

import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet } from '../../lib/api'
import { gtParseIncludesExcludes } from '../../lib/parser'
import { FlameGraph, type FlameGraphNode } from '../../components/chart'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface TraceFlameGraphResponse {
  rootNodes: FlameGraphNode[]
  height: number
  totalSampleCount: number
}

export function TraceFlameGraphPage() {
  const { agentId } = useAgent()
  const [searchParams] = useSearchParams()

  const agentIdParam = searchParams.get('agent-id')
  const effectiveAgentId = agentIdParam !== null ? agentIdParam : (agentId ?? '')
  const traceId = searchParams.get('trace-id') ?? ''
  const checkLiveTraces = searchParams.get('check-live-traces')
  const auxiliary = searchParams.get('auxiliary') === 'true'
  const filter = searchParams.get('filter') || ''
  const truncateRaw = searchParams.get('truncate-branch-percentage')
  const truncatePct = truncateRaw !== null && truncateRaw !== '' ? Number(truncateRaw) : 1.0

  const [rootNodes, setRootNodes] = useState<FlameGraphNode[]>([])
  const [chartHeight, setChartHeight] = useState(0)
  const [totalSampleCount, setTotalSampleCount] = useState(0)
  const [noData, setNoData] = useState(false)
  const [parsingError, setParsingError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    document.title = 'Transactions \u00b7 Glowroot'
    return () => {
      document.querySelectorAll('.d3-flame-graph-tip').forEach((el) => el.remove())
    }
  }, [])

  useEffect(() => {
    let parseResult: { includes: string[]; excludes: string[] }
    try {
      parseResult = gtParseIncludesExcludes(filter)
    } catch (err) {
      setParsingError(err instanceof Error ? err.message : String(err))
      setError(null)
      setNoData(false)
      setRootNodes([])
      setLoading(false)
      return
    }

    setParsingError(null)
    if (!traceId) {
      setError(null)
      setNoData(true)
      setRootNodes([])
      setLoading(false)
      return
    }

    setLoading(true)
    setError(null)
    setNoData(false)

    const query: Record<string, string | string[] | number | undefined> = {
      'agent-id': effectiveAgentId,
      'trace-id': traceId,
      auxiliary: String(auxiliary),
      'truncate-branch-percentage': Number.isFinite(truncatePct) ? truncatePct : 1.0,
    }
    if (parseResult.includes.length > 0) {
      query.include = parseResult.includes
    }
    if (parseResult.excludes.length > 0) {
      query.exclude = parseResult.excludes
    }
    if (checkLiveTraces === 'true') {
      query['check-live-traces'] = 'true'
    }

    let cancelled = false
    apiGet<TraceFlameGraphResponse>('/backend/transaction/traces/flame-graph', query)
      .then((data) => {
        if (cancelled) return
        if (!data.rootNodes || data.rootNodes.length === 0) {
          setNoData(true)
          setRootNodes([])
          setTotalSampleCount(0)
          return
        }
        setRootNodes(data.rootNodes)
        setChartHeight(data.height)
        setTotalSampleCount(data.totalSampleCount)
      })
      .catch((err) => {
        if (!cancelled) setError(err)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [effectiveAgentId, traceId, checkLiveTraces, auxiliary, filter, truncatePct])

  return (
    <div className="p-6 space-y-4">
      <div>
        <h2 className="text-xl font-semibold text-gray-900">
          Flame graph{auxiliary ? ' (Auxiliary thread)' : ''}
        </h2>
        {filter && (
          <div className="mt-1 text-sm text-gray-600">
            Profile tree filter: <span className="font-mono text-gray-800">{filter}</span>
          </div>
        )}
      </div>

      {parsingError && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          {parsingError}
        </div>
      )}

      {loading && <PageSpinner />}
      {!loading && error != null && <HttpError error={error} />}

      {!loading && !error && !parsingError && noData && (
        <div className="rounded-lg border bg-white p-4 text-sm text-gray-500">
          No data for this time period
        </div>
      )}

      {!loading && !error && !parsingError && rootNodes.length > 0 && (
        <div className="rounded-lg border bg-white p-4 space-y-3">
          <p className="text-xs text-gray-500">
            Click a frame to zoom in. Click the root frame to zoom back out. Hover over a frame to inspect method details and sample counts.
          </p>
          <FlameGraph
            rootNodes={rootNodes}
            height={chartHeight}
            totalSampleCount={totalSampleCount}
          />
        </div>
      )}
    </div>
  )
}
