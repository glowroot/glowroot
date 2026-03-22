/*
 * TransactionServiceCallsPage — data table of outbound service calls.
 *
 * Backend: GET /backend/transaction/service-calls
 */

import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTransaction } from '../../contexts/TransactionContext'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet } from '../../lib/api'
import { formatMillis } from '../../lib/formatting'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface ServiceCallItem {
  type: string
  text: string
  totalDurationNanos: number
  executionCount: number
  // computed
  timePerExecution?: number
}

type SortAttr = 'total-time' | 'execution-count' | 'time-per-execution'

export function TransactionServiceCallsPage() {
  const txn = useTransaction()
  const { agentRollupId } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()

  const [serviceCalls, setServiceCalls] = useState<ServiceCallItem[]>([])
  const [types, setTypes] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [overwritten, setOverwritten] = useState(false)

  const selectedType = searchParams.get('type') || ''
  const sortAttribute: SortAttr = (searchParams.get('sort-attribute') as SortAttr) || 'total-time'
  const sortAsc = searchParams.get('sort-direction') === 'asc'

  useEffect(() => {
    if (agentRollupId === null || !txn.transactionType) return
    setLoading(true)
    setError(null)

    const query: Record<string, string | number> = {
      'agent-rollup-id': agentRollupId,
      'transaction-type': txn.transactionType,
      from: Math.floor(txn.range.chartFrom / 60000) * 60000,
      to: Math.ceil(txn.range.chartTo / 60000) * 60000,
    }
    if (txn.transactionName) {
      query['transaction-name'] = txn.transactionName
    }

    apiGet<ServiceCallItem[] | { overwritten: boolean }>('/backend/transaction/service-calls', query)
      .then((data) => {
        if (data && 'overwritten' in data && data.overwritten) {
          setOverwritten(true)
          setServiceCalls([])
          return
        }
        setOverwritten(false)
        const items = data as ServiceCallItem[]
        const typeMap: Record<string, number> = {}
        for (const sc of items) {
          sc.timePerExecution = sc.totalDurationNanos / (1000000 * sc.executionCount)
          typeMap[sc.type] = (typeMap[sc.type] || 0) + sc.totalDurationNanos
        }
        setServiceCalls(items)
        setTypes(Object.keys(typeMap).sort((a, b) => (typeMap[b] || 0) - (typeMap[a] || 0)))
      })
      .catch(setError)
      .finally(() => setLoading(false))
  }, [agentRollupId, txn.transactionType, txn.transactionName, txn.range.chartFrom, txn.range.chartTo, txn.refreshCounter])

  const displayed = useMemo(() => {
    let list = selectedType ? serviceCalls.filter((sc) => sc.type === selectedType) : serviceCalls
    list = [...list]
    const sortKey = (sc: ServiceCallItem): number => {
      if (sortAttribute === 'total-time') return sc.totalDurationNanos
      if (sortAttribute === 'execution-count') return sc.executionCount
      if (sortAttribute === 'time-per-execution') return sc.timePerExecution ?? 0
      return sc.totalDurationNanos
    }
    list.sort((a, b) => {
      const diff = sortKey(b) - sortKey(a)
      return sortAsc ? -diff : diff
    })
    return list
  }, [serviceCalls, selectedType, sortAttribute, sortAsc])

  function toggleSort(attr: SortAttr) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      if (sortAttribute === attr && !sortAsc) {
        next.set('sort-direction', 'asc')
      } else {
        next.delete('sort-direction')
      }
      if (attr !== 'total-time') {
        next.set('sort-attribute', attr)
      } else {
        next.delete('sort-attribute')
      }
      return next
    })
  }

  function ariaSort(attr: SortAttr): 'ascending' | 'descending' | undefined {
    if (sortAttribute !== attr) return undefined
    return sortAsc ? 'ascending' : 'descending'
  }

  function sortIcon(attr: SortAttr) {
    if (sortAttribute !== attr) return ''
    return sortAsc ? ' ↑' : ' ↓'
  }

  if (loading && serviceCalls.length === 0) return <PageSpinner />
  if (error && serviceCalls.length === 0) return <HttpError error={error} />

  if (overwritten) {
    return (
      <div className="rounded-lg border bg-white p-4 text-sm text-orange-700">
        The service call data has expired.
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {/* Type filter */}
      {types.length > 1 && (
        <div className="flex items-center gap-2 text-sm">
          <label className="text-gray-500">Filter by type:</label>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={selectedType}
            onChange={(e) => {
              setSearchParams((prev) => {
                const next = new URLSearchParams(prev)
                if (e.target.value) {
                  next.set('type', e.target.value)
                } else {
                  next.delete('type')
                }
                return next
              })
            }}
          >
            <option value="">All types</option>
            {types.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </div>
      )}

      {displayed.length > 0 ? (
        <div className="rounded-lg border bg-white overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-gray-500 bg-gray-50">
                <th className="py-2 px-3 font-normal">Service call</th>
                <th
                  aria-sort={ariaSort('total-time')}
                  className="py-2 px-3 font-normal text-right cursor-pointer hover:text-gray-700 whitespace-nowrap"
                  onClick={() => toggleSort('total-time')}
                >
                  Total time{sortIcon('total-time')}
                </th>
                <th
                  aria-sort={ariaSort('execution-count')}
                  className="py-2 px-3 font-normal text-right cursor-pointer hover:text-gray-700 whitespace-nowrap"
                  onClick={() => toggleSort('execution-count')}
                >
                  Count{sortIcon('execution-count')}
                </th>
                <th
                  aria-sort={ariaSort('time-per-execution')}
                  className="py-2 px-3 font-normal text-right cursor-pointer hover:text-gray-700 whitespace-nowrap"
                  onClick={() => toggleSort('time-per-execution')}
                >
                  Time/exec{sortIcon('time-per-execution')}
                </th>
              </tr>
            </thead>
            <tbody>
              {displayed.map((sc, i) => (
                <tr key={i} className="border-b last:border-b-0 hover:bg-gray-50">
                  <td className="py-2 px-3">
                    <span className="font-mono text-xs break-all">{sc.text}</span>
                    {selectedType === '' && types.length > 1 && (
                      <span className="ml-2 text-xs text-gray-400">[{sc.type}]</span>
                    )}
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums whitespace-nowrap">
                    {formatMillis(sc.totalDurationNanos / 1000000)} ms
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums">
                    {sc.executionCount.toLocaleString()}
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums whitespace-nowrap">
                    {formatMillis(sc.timePerExecution ?? 0)} ms
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="rounded-lg border bg-white p-4 text-sm text-gray-500">
          No service calls for selected time range.
        </div>
      )}
    </div>
  )
}
