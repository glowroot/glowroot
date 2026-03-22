/*
 * TransactionQueriesPage — data table of database queries with sorting and detail modal.
 *
 * Backend: GET /backend/transaction/queries
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTransaction } from '../../contexts/TransactionContext'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet } from '../../lib/api'
import { formatMillis, formatCount } from '../../lib/formatting'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface QueryItem {
  queryType: string
  truncatedQueryText: string
  fullQueryTextSha1?: string
  totalDurationNanos: number
  executionCount: number
  totalRows?: number
  // computed
  timePerExecution?: number
  rowsPerExecution?: number
  text?: string
}

type SortAttr = 'total-time' | 'execution-count' | 'time-per-execution' | 'rows-per-execution'

function formatSql(text: string): string {
  return text
    .replace(
      /\s+\b(INNER\s+JOIN|LEFT\s+JOIN|RIGHT\s+JOIN|FULL\s+JOIN|CROSS\s+JOIN|JOIN|FROM|WHERE|AND|OR|GROUP\s+BY|HAVING|ORDER\s+BY|LIMIT|VALUES|SET)\b/gi,
      '\n$1'
    )
    .trim()
}

export function TransactionQueriesPage() {
  const txn = useTransaction()
  const { agentRollupId } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()

  const [queries, setQueries] = useState<QueryItem[]>([])
  const [limitExceededBucket, setLimitExceededBucket] = useState<QueryItem | undefined>()
  const [queryTypes, setQueryTypes] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [overwritten, setOverwritten] = useState(false)

  // URL-synced modal state
  const modalQueryType = searchParams.get('modal-query-type') || ''
  const modalQueryText = searchParams.get('modal-query-text') || ''
  const modalQueryTextSha1 = searchParams.get('modal-query-text-sha1') || ''
  const modalOpen = Boolean(modalQueryText || modalQueryTextSha1)

  const [unformattedQuery, setUnformattedQuery] = useState('')
  const [formattedQuery, setFormattedQuery] = useState('')
  const [showFormatted, setShowFormatted] = useState(false)
  const [modalLoading, setModalLoading] = useState(false)
  const [modalExpired, setModalExpired] = useState(false)
  const [modalError, setModalError] = useState(false)

  const queryType = searchParams.get('query-type') || ''
  const sortAttribute: SortAttr = (searchParams.get('sort-attribute') as SortAttr) || 'total-time'
  const sortAsc = searchParams.get('sort-direction') === 'asc'

  // Fetch queries
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

    apiGet<QueryItem[] | { overwritten: boolean }>('/backend/transaction/queries', query)
      .then((data) => {
        if (data && 'overwritten' in data && data.overwritten) {
          setOverwritten(true)
          setQueries([])
          return
        }
        setOverwritten(false)
        const items = data as QueryItem[]

        // Compute derived fields
        const types: Record<string, number> = {}
        for (const q of items) {
          q.timePerExecution = q.totalDurationNanos / (1000000 * q.executionCount)
          q.rowsPerExecution = q.totalRows !== undefined
            ? q.totalRows / q.executionCount
            : undefined
          types[q.queryType] = (types[q.queryType] || 0) + q.totalDurationNanos
        }

        // Extract LIMIT EXCEEDED BUCKET
        let leb: QueryItem | undefined
        const filtered = items.filter((q) => {
          if (q.truncatedQueryText === 'LIMIT EXCEEDED BUCKET') {
            leb = q
            return false
          }
          return true
        })
        setLimitExceededBucket(leb)
        setQueries(filtered)

        const sortedTypes = Object.keys(types).sort(
          (a, b) => (types[b] || 0) - (types[a] || 0)
        )
        setQueryTypes(sortedTypes)
      })
      .catch(setError)
      .finally(() => setLoading(false))
  }, [agentRollupId, txn.transactionType, txn.transactionName, txn.range.chartFrom, txn.range.chartTo, txn.refreshCounter])

  // Sort and filter
  const displayed = useMemo(() => {
    let list = queryType ? queries.filter((q) => q.queryType === queryType) : queries
    list = [...list]

    const sortKey = (q: QueryItem): number => {
      if (sortAttribute === 'total-time') return q.totalDurationNanos
      if (sortAttribute === 'execution-count') return q.executionCount
      if (sortAttribute === 'time-per-execution') return q.timePerExecution ?? 0
      if (sortAttribute === 'rows-per-execution') return q.rowsPerExecution ?? 0
      return q.totalDurationNanos
    }

    list.sort((a, b) => {
      const diff = sortKey(b) - sortKey(a)
      return sortAsc ? -diff : diff
    })
    return list
  }, [queries, queryType, sortAttribute, sortAsc])

  function toggleSort(attr: SortAttr) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      if (attr === 'total-time' && sortAttribute !== 'total-time') {
        next.delete('sort-attribute')
        next.delete('sort-direction')
      } else if (attr !== 'total-time') {
        next.set('sort-attribute', attr)
      }
      if (sortAttribute === attr && !sortAsc) {
        next.set('sort-direction', 'asc')
      } else {
        next.delete('sort-direction')
      }
      if (attr !== 'total-time') {
        next.set('sort-attribute', attr)
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

  const closeModal = useCallback(() => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.delete('modal-query-type')
      next.delete('modal-query-text')
      next.delete('modal-query-text-sha1')
      return next
    })
  }, [setSearchParams])

  // Sync modal content from URL params
  useEffect(() => {
    setUnformattedQuery('')
    setFormattedQuery('')
    setShowFormatted(false)
    setModalExpired(false)
    setModalError(false)
    if (!modalOpen) {
      setModalLoading(false)
      return
    }

    function applyText(text: string) {
      setUnformattedQuery(text)
      if (modalQueryType === 'SQL') {
        const formatted = formatSql(text)
        if (formatted !== text) {
          setFormattedQuery(formatted)
          setShowFormatted(true)
        }
      }
    }

    if (modalQueryTextSha1) {
      if (agentRollupId === null) return
      let cancelled = false
      setModalLoading(true)
      apiGet<{ fullText?: string; expired?: boolean }>('/backend/transaction/full-query-text', {
        'agent-rollup-id': agentRollupId,
        'full-text-sha1': modalQueryTextSha1,
      })
        .then((resp) => {
          if (cancelled) return
          if (resp.expired) {
            setModalExpired(true)
          } else {
            applyText(resp.fullText || '')
          }
        })
        .catch(() => {
          if (!cancelled) setModalError(true)
        })
        .finally(() => {
          if (!cancelled) setModalLoading(false)
        })
      return () => {
        cancelled = true
      }
    } else if (modalQueryText) {
      applyText(modalQueryText)
      setModalLoading(false)
    }
  }, [modalOpen, modalQueryType, modalQueryText, modalQueryTextSha1, agentRollupId])

  useEffect(() => {
    if (!modalOpen) return
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        closeModal()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [modalOpen, closeModal])

  // Open query detail via URL params
  function openQueryDetail(q: QueryItem) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.set('modal-query-type', q.queryType)
      if (q.fullQueryTextSha1) {
        next.set('modal-query-text-sha1', q.fullQueryTextSha1)
        next.delete('modal-query-text')
      } else {
        next.set('modal-query-text', q.truncatedQueryText)
        next.delete('modal-query-text-sha1')
      }
      return next
    })
  }

  if (loading && queries.length === 0) return <PageSpinner />
  if (error && queries.length === 0) return <HttpError error={error} />

  if (overwritten) {
    return (
      <div className="rounded-lg border bg-white p-4 text-sm text-orange-700">
        The query data has expired.
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {/* Type filter */}
      {queryTypes.length > 1 && (
        <div className="flex items-center gap-2 text-sm">
          <label className="text-gray-500">Filter by type:</label>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={queryType}
            onChange={(e) => {
              setSearchParams((prev) => {
                const next = new URLSearchParams(prev)
                if (e.target.value) {
                  next.set('query-type', e.target.value)
                } else {
                  next.delete('query-type')
                }
                return next
              })
            }}
          >
            <option value="">All types</option>
            {queryTypes.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </div>
      )}

      {/* Queries table */}
      {displayed.length > 0 ? (
        <div className="rounded-lg border bg-white overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-gray-500 bg-gray-50">
                <th className="py-2 px-3 font-normal">Query</th>
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
                <th
                  aria-sort={ariaSort('rows-per-execution')}
                  className="py-2 px-3 font-normal text-right cursor-pointer hover:text-gray-700 whitespace-nowrap"
                  onClick={() => toggleSort('rows-per-execution')}
                >
                  Rows/exec{sortIcon('rows-per-execution')}
                </th>
              </tr>
            </thead>
            <tbody>
              {displayed.map((q, i) => (
                <tr key={i} className="border-b last:border-b-0 hover:bg-gray-50">
                  <td className="py-2 px-3">
                    <button
                      className="text-left text-blue-600 hover:underline font-mono text-xs break-all"
                      onClick={() => openQueryDetail(q)}
                    >
                      {q.truncatedQueryText.length > 80
                        ? q.truncatedQueryText.substring(0, 77) + '...'
                        : q.truncatedQueryText}
                    </button>
                    {queryType === '' && queryTypes.length > 1 && (
                      <span className="ml-2 text-xs text-gray-400">[{q.queryType}]</span>
                    )}
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums whitespace-nowrap">
                    {formatMillis(q.totalDurationNanos / 1000000)} ms
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums">
                    {q.executionCount.toLocaleString()}
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums whitespace-nowrap">
                    {formatMillis(q.timePerExecution ?? 0)} ms
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums">
                    {q.rowsPerExecution !== undefined
                      ? formatCount(q.rowsPerExecution)
                      : ''}
                  </td>
                </tr>
              ))}
              {limitExceededBucket && (
                <tr className="border-b last:border-b-0 bg-orange-50/40 text-gray-700">
                  <td className="py-2 px-3 font-mono text-xs">
                    {limitExceededBucket.truncatedQueryText}
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums whitespace-nowrap">
                    {formatMillis(limitExceededBucket.totalDurationNanos / 1000000)} ms
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums">
                    {limitExceededBucket.executionCount.toLocaleString()}
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums whitespace-nowrap">
                    {formatMillis(limitExceededBucket.timePerExecution ?? 0)} ms
                  </td>
                  <td className="py-2 px-3 text-right tabular-nums">
                    {limitExceededBucket.rowsPerExecution !== undefined
                      ? formatCount(limitExceededBucket.rowsPerExecution)
                      : ''}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="rounded-lg border bg-white p-4 text-sm text-gray-500">
          No queries for selected time range.
        </div>
      )}

      {/* Query detail modal */}
      {modalOpen && (
        <div
          id="queryModal"
          role="dialog"
          aria-modal="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={closeModal}
        >
          <div
            className="bg-white rounded-lg shadow-lg max-w-3xl w-full mx-4 max-h-[80vh] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-4 py-3 border-b">
              <h3 className="text-sm font-medium">{modalQueryType || 'Query'} Query</h3>
              <div className="flex items-center gap-2">
                {!modalLoading && !modalExpired && !modalError && (
                  <button
                    type="button"
                    className="text-xs text-blue-600 hover:underline"
                    onClick={() =>
                      navigator.clipboard?.writeText(
                        showFormatted && formattedQuery ? formattedQuery : unformattedQuery
                      )
                    }
                  >
                    Copy
                  </button>
                )}
                <button
                  type="button"
                  aria-label="Close"
                  className="text-gray-400 hover:text-gray-600 text-lg"
                  onClick={closeModal}
                >
                  ×
                </button>
              </div>
            </div>
            <div className="p-4 overflow-auto flex-1 space-y-3">
              {modalLoading ? (
                <p className="text-sm text-gray-400">Loading full query text...</p>
              ) : modalExpired ? (
                <p className="text-sm font-semibold text-gray-800">
                  The full text for this query has expired
                </p>
              ) : modalError ? (
                <p className="text-sm text-red-600">An error occurred retrieving the query</p>
              ) : (
                <>
                  {formattedQuery && showFormatted ? (
                    <pre
                      id="formattedQuery"
                      className="text-xs font-mono whitespace-pre-wrap break-all"
                    >
                      {formattedQuery}
                    </pre>
                  ) : (
                    <pre
                      id="unformattedQuery"
                      className="text-xs font-mono whitespace-pre-wrap break-all"
                    >
                      {unformattedQuery}
                    </pre>
                  )}
                  {formattedQuery && (
                    <div>
                      <button
                        type="button"
                        id="formattingToggle"
                        className="text-xs text-blue-600 hover:underline"
                        onClick={() => setShowFormatted((v) => !v)}
                      >
                        {showFormatted ? '(show unformatted)' : '(show formatted)'}
                      </button>
                    </div>
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
