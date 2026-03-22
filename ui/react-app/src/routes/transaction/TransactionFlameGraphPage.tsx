/*
 * TransactionFlameGraphPage — Standalone D3 flame graph visualization of thread profile.
 *
 * Backend: GET /backend/transaction/flame-graph
 */

import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet } from '../../lib/api'
import { applyLast } from '../../lib/chart-utils'
import { gtParseIncludesExcludes } from '../../lib/parser'
import { FlameGraph, type FlameGraphNode } from '../../components/chart'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface FlameGraphResponse {
  rootNodes: FlameGraphNode[]
  height: number
  totalSampleCount: number
}

export function TransactionFlameGraphPage() {
  const { agentRollup, agentRollupId } = useAgent()
  const { layout } = useLayout()
  const [searchParams] = useSearchParams()

  const queryAgentRollupId = searchParams.get('agent-rollup-id') ?? searchParams.get('agent-id')
  const effectiveAgentRollupId = queryAgentRollupId !== null ? queryAgentRollupId : agentRollupId
  const transactionType =
    searchParams.get('transaction-type') ||
    agentRollup?.defaultTransactionType ||
    agentRollup?.transactionTypes?.[0] ||
    ''
  const transactionName = searchParams.get('transaction-name') || ''
  const fromParam = searchParams.get('from')
  const toParam = searchParams.get('to')
  const lastParam = searchParams.get('last')
  const auxiliary = searchParams.get('auxiliary') === 'true'
  const filter = searchParams.get('filter') || ''
  const truncateRaw = searchParams.get('truncate-branch-percentage')
  const truncatePct = truncateRaw !== null && truncateRaw !== '' ? Number(truncateRaw) : 1.0

  const [rootNodes, setRootNodes] = useState<FlameGraphNode[]>([])
  const [chartHeight, setChartHeight] = useState(0)
  const [noData, setNoData] = useState(false)
  const [parsingError, setParsingError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    if (effectiveAgentRollupId === null || !transactionType) return

    let from = fromParam !== null ? Number(fromParam) : NaN
    let to = toParam !== null ? Number(toParam) : NaN
    if (!Number.isFinite(from) || !Number.isFinite(to)) {
      const lastMs =
        lastParam !== null && Number.isFinite(Number(lastParam)) && Number(lastParam) > 0
          ? Number(lastParam)
          : 4 * 60 * 60 * 1000
      const range = applyLast(lastMs, layout.rollupConfigs, layout.rollupExpirationMillis)
      from = range.chartFrom
      to = range.chartTo
    }

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
    setLoading(true)
    setError(null)
    setNoData(false)

    const query: Record<string, string | string[] | number | undefined> = {
      'agent-rollup-id': effectiveAgentRollupId,
      'transaction-type': transactionType,
      from,
      to,
      auxiliary: String(auxiliary),
      'truncate-branch-percentage': Number.isFinite(truncatePct) ? truncatePct : 1.0,
    }
    if (transactionName) {
      query['transaction-name'] = transactionName
    }
    if (parseResult.includes.length) {
      query['include'] = parseResult.includes
    }
    if (parseResult.excludes.length) {
      query['exclude'] = parseResult.excludes
    }

    let cancelled = false
    apiGet<FlameGraphResponse>('/backend/transaction/flame-graph', query)
      .then((data) => {
        if (cancelled) return
        if (data.rootNodes.length === 0) {
          setNoData(true)
          setRootNodes([])
          return
        }
        setRootNodes(data.rootNodes)
        setChartHeight(data.height)
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
  }, [
    effectiveAgentRollupId,
    transactionType,
    transactionName,
    fromParam,
    toParam,
    lastParam,
    auxiliary,
    filter,
    truncatePct,
    layout.rollupConfigs,
    layout.rollupExpirationMillis,
  ])

  return (
    <div className="p-6 space-y-4">
      <div>
        <h2 className="text-xl font-semibold text-gray-900">
          Flame graph{auxiliary ? ' (Auxiliary thread)' : ''}
        </h2>
        <div className="mt-1 text-sm text-gray-600 space-y-0.5">
          {transactionType && (
            <div>
              Transaction type: <span className="font-medium text-gray-800">{transactionType}</span>
              {transactionName && (
                <>
                  {' '}
                  &mdash; Transaction name:{' '}
                  <span className="font-medium text-gray-800">{transactionName}</span>
                </>
              )}
            </div>
          )}
          {filter && (
            <div>
              Profile tree filter: <span className="font-mono text-gray-800">{filter}</span>
            </div>
          )}
        </div>
      </div>

      {parsingError && (
        <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {parsingError}
        </div>
      )}

      {loading && <PageSpinner />}
      {!loading && error != null && <HttpError error={error} />}

      {!loading && !error && !parsingError && noData && (
        <div className="rounded-lg border bg-white p-4 text-sm text-gray-500">
          No profile data for selected time range.
        </div>
      )}

      {!loading && !error && !parsingError && rootNodes.length > 0 && (
        <div className="rounded-lg border bg-white p-4 space-y-3">
          <p className="text-xs text-gray-500">
            Click a frame to zoom in. Click the root frame to zoom back out. Hover over a frame to inspect method details and sample counts.
          </p>
          <FlameGraph rootNodes={rootNodes} height={chartHeight} />
        </div>
      )}
    </div>
  )
}
