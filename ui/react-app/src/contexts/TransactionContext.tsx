/*
 * TransactionContext — shared state for transaction and error pages.
 *
 * Manages transactionType, transactionName, chart range, sidebar summaries,
 * and tab trace count. These are all shared across the nested transaction/error pages.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAgent } from './AgentContext'
import { useLayout } from './LayoutContext'
import { apiGet } from '../lib/api'
import { useChartRange, type ChartRange, type ChartRangeActions } from '../hooks/useChartRange'
import { formatBytes, formatMillis } from '../lib/formatting'

// --- Summary types ---

export interface TransactionSummary {
  transactionName: string
  totalDurationNanos: number
  transactionCount: number
  errorCount?: number
  totalCpuNanos?: number
  totalAllocatedBytes?: number
}

export interface OverallSummary {
  totalDurationNanos: number
  transactionCount: number
  errorCount?: number
  totalCpuNanos?: number
  totalAllocatedBytes?: number
}

// --- Sort orders ---

export const TRANSACTION_SORT_ORDERS: Record<string, string> = {
  'total-time': 'By total time (%)',
  'average-time': 'By average time',
  'throughput': 'By throughput (per min)',
  'total-cpu-time': 'By total CPU time (%)',
  'average-cpu-time': 'By average CPU time',
  'total-allocated-memory': 'By total allocated memory (%)',
  'average-allocated-memory': 'By average allocated memory',
}

export const ERROR_SORT_ORDERS: Record<string, string> = {
  'error-count': 'By error count',
  'error-rate': 'By error rate',
}

// --- Context value ---

export interface TransactionContextValue {
  shortName: 'transaction' | 'error'
  headerDisplay: string
  transactionType: string
  transactionName: string
  range: ChartRange
  rangeActions: ChartRangeActions
  refreshCounter: number
  overallSummary: OverallSummary | null
  transactionSummaries: TransactionSummary[]
  moreSummariesAvailable: boolean
  summarySortOrder: string
  setSummarySortOrder: (order: string) => void
  summaryLimit: number
  showMoreSummaries: () => void
  summaryValueFn: (summary: TransactionSummary | OverallSummary) => string
  summaryLoading: boolean
  traceCount: number | undefined
  displayedTraceCount: number | undefined
  setDisplayedTraceCount: (count: number | undefined) => void
  buildTabQueryString: () => string
}

const TransactionCtx = createContext<TransactionContextValue | null>(null)

export function useTransaction(): TransactionContextValue {
  const ctx = useContext(TransactionCtx)
  if (!ctx) throw new Error('useTransaction must be used within TransactionProvider')
  return ctx
}

interface TransactionProviderProps {
  shortName: 'transaction' | 'error'
  children: ReactNode
}

export function TransactionProvider({ shortName, children }: TransactionProviderProps) {
  const { layout } = useLayout()
  const { agentRollup, agentRollupId } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()
  const [displayedTraceCount, setDisplayedTraceCount] = useState<number | undefined>(undefined)

  const headerDisplay =
    shortName === 'transaction' ? 'Transactions' : 'Errors'

  const defaultSortOrder = shortName === 'transaction' ? 'total-time' : 'error-count'
  const validSortOrders = shortName === 'transaction' ? TRANSACTION_SORT_ORDERS : ERROR_SORT_ORDERS

  const resolveSortOrder = useCallback(
    (raw: string | null): string =>
      raw && Object.prototype.hasOwnProperty.call(validSortOrders, raw)
        ? raw
        : defaultSortOrder,
    [validSortOrders, defaultSortOrder]
  )

  // --- Read URL params ---
  const transactionType =
    searchParams.get('transaction-type') ||
    agentRollup?.defaultTransactionType ||
    ''
  const transactionName = searchParams.get('transaction-name') || ''

  // Validate transaction-type against agentRollup and persist default into URL
  useEffect(() => {
    if (!agentRollup) return
    const current = searchParams.get('transaction-type')
    const validTypes = agentRollup.transactionTypes ?? []
    const isValid =
      current !== null &&
      current !== '' &&
      (validTypes.length === 0 || validTypes.includes(current))
    if (isValid) return
    const resolved = agentRollup.defaultTransactionType || validTypes[0] || ''
    if (!resolved || resolved === current) return
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        next.set('transaction-type', resolved)
        return next
      },
      { replace: true }
    )
  }, [agentRollup, searchParams, setSearchParams])

  // --- Chart range ---
  const [range, rangeActions, refreshCounter] = useChartRange(4 * 60 * 60 * 1000)

  // --- Summary state ---
  const [overallSummary, setOverallSummary] = useState<OverallSummary | null>(null)
  const [transactionSummaries, setTransactionSummaries] = useState<TransactionSummary[]>([])
  const [moreSummariesAvailable, setMoreSummariesAvailable] = useState(false)
  const [summaryLimit, setSummaryLimit] = useState(10)
  const [summaryLoading, setSummaryLoading] = useState(false)
  const [summarySortOrder, setSummarySortOrderState] = useState(() =>
    resolveSortOrder(searchParams.get('summary-sort-order'))
  )

  // Keep sort order synchronized with URL and remove invalid values (e.g. total-time on error pages)
  useEffect(() => {
    const raw = searchParams.get('summary-sort-order')
    const resolved = resolveSortOrder(raw)
    setSummarySortOrderState(resolved)
    if (raw !== null && raw !== resolved) {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          next.delete('summary-sort-order')
          return next
        },
        { replace: true }
      )
    }
  }, [searchParams, resolveSortOrder, setSearchParams])

  const effectiveSortOrder = resolveSortOrder(summarySortOrder)

  const setSummarySortOrder = useCallback(
    (order: string) => {
      const resolved = resolveSortOrder(order)
      setSummarySortOrderState(resolved)
      setSearchParams((prev) => {
        const next = new URLSearchParams(prev)
        if (resolved === defaultSortOrder) {
          next.delete('summary-sort-order')
        } else {
          next.set('summary-sort-order', resolved)
        }
        return next
      })
    },
    [defaultSortOrder, resolveSortOrder, setSearchParams]
  )

  const showMoreSummaries = useCallback(() => {
    setSummaryLimit((prev) => prev * 2)
  }, [])

  // --- Trace count ---
  const [traceCount, setTraceCount] = useState<number | undefined>(undefined)

  // --- Fetch summaries ---
  const summaryFetchIdRef = useRef(0)
  useEffect(() => {
    if (agentRollupId === null || !transactionType) return
    const fetchId = ++summaryFetchIdRef.current
    setSummaryLoading(true)

    const query: Record<string, string | number> = {
      'agent-rollup-id': agentRollupId,
      'transaction-type': transactionType,
      from: Math.floor(range.chartFrom / 60000) * 60000,
      to: Math.ceil(range.chartTo / 60000) * 60000,
      'sort-order': effectiveSortOrder,
      limit: summaryLimit,
    }

    apiGet<{
      overall: OverallSummary
      transactions: TransactionSummary[]
      moreAvailable: boolean
    }>(`/backend/${shortName}/summaries`, query)
      .then((data) => {
        if (fetchId !== summaryFetchIdRef.current) return
        setOverallSummary(data.overall)
        setTransactionSummaries(data.transactions)
        setMoreSummariesAvailable(data.moreAvailable)
      })
      .catch(() => {
        // silently fail — summaries are secondary
      })
      .finally(() => {
        if (fetchId === summaryFetchIdRef.current) setSummaryLoading(false)
      })
  }, [
    agentRollupId,
    transactionType,
    range.chartFrom,
    range.chartTo,
    effectiveSortOrder,
    summaryLimit,
    shortName,
    refreshCounter,
  ])

  // --- Fetch trace count ---
  useEffect(() => {
    const perms =
      shortName === 'transaction'
        ? agentRollup?.permissions?.transaction?.traces
        : agentRollup?.permissions?.error?.traces
    if (!perms || agentRollupId === null || !transactionType) {
      setTraceCount(undefined)
      return
    }
    const query: Record<string, string | number> = {
      'agent-rollup-id': agentRollupId,
      'transaction-type': transactionType,
      from: range.chartFrom,
      to: range.chartTo,
    }
    if (transactionName) {
      query['transaction-name'] = transactionName
    }
    apiGet<number>(`/backend/${shortName}/trace-count`, query)
      .then(setTraceCount)
      .catch(() => setTraceCount(undefined))
  }, [
    agentRollupId,
    transactionType,
    transactionName,
    range.chartFrom,
    range.chartTo,
    shortName,
    agentRollup,
    refreshCounter,
  ])

  // --- Summary value function ---
  const durationMillis = range.chartTo - range.chartFrom
  const summaryValueFn = useCallback(
    (summary: TransactionSummary | OverallSummary): string => {
      if (shortName === 'error') {
        if (effectiveSortOrder === 'error-count') {
          return String(summary.errorCount ?? 0)
        }
        // error-rate
        if (!summary.transactionCount) return ''
        return ((100 * (summary.errorCount ?? 0)) / summary.transactionCount).toFixed(1) + ' %'
      }
      // transaction sort orders
      if (!overallSummary) return ''
      if (effectiveSortOrder === 'total-time') {
        if (!overallSummary.totalDurationNanos) return ''
        return (
          (
            (100 * summary.totalDurationNanos) /
            overallSummary.totalDurationNanos
          ).toFixed(1) + ' %'
        )
      }
      if (effectiveSortOrder === 'average-time') {
        if (!summary.transactionCount) return ''
        return (
          formatMillis(
            summary.totalDurationNanos / (1000000 * summary.transactionCount)
          ) + ' ms'
        )
      }
      if (effectiveSortOrder === 'throughput') {
        return (
          ((60 * 1000 * summary.transactionCount) / durationMillis).toFixed(1) +
          '/min'
        )
      }
      if (effectiveSortOrder === 'total-cpu-time') {
        if (!overallSummary.totalCpuNanos) return ''
        return (
          (
            (100 * (summary.totalCpuNanos ?? 0)) /
            overallSummary.totalCpuNanos
          ).toFixed(1) + ' %'
        )
      }
      if (effectiveSortOrder === 'average-cpu-time') {
        if (!summary.transactionCount) return ''
        return (
          formatMillis(
            (summary.totalCpuNanos ?? 0) / (1000000 * summary.transactionCount)
          ) + ' ms'
        )
      }
      if (effectiveSortOrder === 'total-allocated-memory') {
        const overallBytes = (overallSummary as OverallSummary & { totalAllocatedBytes?: number })
          .totalAllocatedBytes
        const summaryBytes = (summary as TransactionSummary & { totalAllocatedBytes?: number })
          .totalAllocatedBytes
        if (!overallBytes) return ''
        return ((100 * (summaryBytes ?? 0)) / overallBytes).toFixed(1) + ' %'
      }
      if (effectiveSortOrder === 'average-allocated-memory') {
        if (!summary.transactionCount) return ''
        const summaryBytes =
          (summary as TransactionSummary & { totalAllocatedBytes?: number })
            .totalAllocatedBytes ?? 0
        return formatBytes(summaryBytes / summary.transactionCount)
      }
      return ''
    },
    [shortName, effectiveSortOrder, overallSummary, durationMillis]
  )

  // --- Build tab query string ---
  const buildTabQueryString = useCallback(() => {
    const params = new URLSearchParams()
    if (layout.central && agentRollupId) {
      if (searchParams.has('agent-id')) {
        params.set('agent-id', agentRollupId)
      } else {
        params.set('agent-rollup-id', agentRollupId)
      }
    }
    if (transactionType) {
      params.set('transaction-type', transactionType)
    }
    if (transactionName) {
      params.set('transaction-name', transactionName)
    }
    if (effectiveSortOrder && effectiveSortOrder !== defaultSortOrder) {
      params.set('summary-sort-order', effectiveSortOrder)
    }
    if (range.last && range.last !== 4 * 60 * 60 * 1000) {
      params.set('last', String(range.last))
    } else if (!range.last) {
      params.set('from', String(Math.floor(range.chartFrom / 60000) * 60000))
      params.set('to', String(Math.ceil(range.chartTo / 60000) * 60000))
    }
    const qs = params.toString()
    return qs ? '?' + qs : ''
  }, [layout.central, agentRollupId, searchParams, transactionType, transactionName, defaultSortOrder, effectiveSortOrder, range])

  const value = useMemo<TransactionContextValue>(
    () => ({
      shortName,
      headerDisplay,
      transactionType,
      transactionName,
      range,
      rangeActions,
      refreshCounter,
      overallSummary,
      transactionSummaries,
      moreSummariesAvailable,
      summarySortOrder: effectiveSortOrder,
      setSummarySortOrder,
      summaryLimit,
      showMoreSummaries,
      summaryValueFn,
      summaryLoading,
      traceCount,
      displayedTraceCount,
      setDisplayedTraceCount,
      buildTabQueryString,
    }),
    [
      shortName,
      headerDisplay,
      transactionType,
      transactionName,
      range,
      rangeActions,
      refreshCounter,
      overallSummary,
      transactionSummaries,
      moreSummariesAvailable,
      effectiveSortOrder,
      setSummarySortOrder,
      summaryLimit,
      showMoreSummaries,
      summaryValueFn,
      summaryLoading,
      traceCount,
      displayedTraceCount,
      buildTabQueryString,
    ]
  )

  return (
    <TransactionCtx.Provider value={value}>{children}</TransactionCtx.Provider>
  )
}
