/*
 * TransactionTracesPage — scatter plot of trace points with filter form and URL sync.
 *
 * Backend: GET /backend/{transaction|error}/points
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTransaction } from '../../contexts/TransactionContext'
import { useAgent } from '../../contexts/AgentContext'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, getContextPath } from '../../lib/api'
import { encodeObject } from '../../lib/query-strings'
import { TracePointChart, type TracePoint } from '../../components/chart'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { TraceModal } from '../../components/trace/TraceModal'

interface PointsResponse {
  normalPoints: Array<[number, number, string, string, boolean?]>
  errorPoints: Array<[number, number, string, string, boolean?]>
  partialPoints: Array<[number, number, string, string, boolean?]>
  expired?: boolean
  limitExceeded?: boolean
}

interface TraceFilterCriteria {
  durationComparator: 'greater' | 'less' | 'between'
  durationLow: string
  durationHigh: string
  headlineComparator: string
  headline: string
  errorMessageComparator: string
  errorMessage: string
  userComparator: string
  user: string
  attributeName: string
  attributeValueComparator: string
  attributeValue: string
  limit: number
}

function toTracePoints(
  raw: Array<[number, number, string, string, boolean?]>,
  kind: 'normal' | 'error' | 'partial'
): TracePoint[] {
  return raw.map((p) => ({
    captureTime: p[0],
    durationMs: p[1],
    agentId: p[2],
    traceId: p[3],
    checkLiveTraces: p[4],
    error: kind === 'error',
    partial: kind === 'partial',
  }))
}

const TEXT_COMPARATORS = [
  { value: 'begins', label: 'Begins with' },
  { value: 'equals', label: 'Equals' },
  { value: 'ends', label: 'Ends with' },
  { value: 'contains', label: 'Contains' },
  { value: 'not-contains', label: 'Does not contain' },
]

const DURATION_COMPARATORS: Array<{ value: 'greater' | 'less' | 'between'; label: string }> = [
  { value: 'greater', label: 'Greater than' },
  { value: 'less', label: 'Less than' },
  { value: 'between', label: 'Between' },
]

const LIMIT_OPTIONS = [100, 200, 500, 1000, 2000, 5000]

const DEFAULT_FILTER: TraceFilterCriteria = {
  durationComparator: 'greater',
  durationLow: '',
  durationHigh: '',
  headlineComparator: 'begins',
  headline: '',
  errorMessageComparator: 'begins',
  errorMessage: '',
  userComparator: 'begins',
  user: '',
  attributeName: '',
  attributeValueComparator: 'begins',
  attributeValue: '',
  limit: 500,
}

function parseFilterFromParams(params: URLSearchParams): TraceFilterCriteria {
  const lowParam = params.get('duration-millis-low') ?? ''
  const highParam = params.get('duration-millis-high') ?? ''
  const durationComparator: 'greater' | 'less' | 'between' =
    lowParam && highParam ? 'between' : highParam ? 'less' : 'greater'
  const limitRaw = Number(params.get('limit'))
  return {
    durationComparator,
    durationLow: lowParam,
    durationHigh: highParam,
    headlineComparator: params.get('headline-comparator') || 'begins',
    headline: params.get('headline') || '',
    errorMessageComparator: params.get('error-message-comparator') || 'begins',
    errorMessage: params.get('error-message') || '',
    userComparator: params.get('user-comparator') || 'begins',
    user: params.get('user') || '',
    attributeName: params.get('custom-attribute-name') || '',
    attributeValueComparator: params.get('custom-attribute-value-comparator') || 'begins',
    attributeValue: params.get('custom-attribute-value') || '',
    limit: Number.isFinite(limitRaw) && limitRaw > 0 ? limitRaw : 500,
  }
}

function getFilterUrlKey(params: URLSearchParams): string {
  const keys = [
    'duration-millis-low',
    'duration-millis-high',
    'headline-comparator',
    'headline',
    'error-message-comparator',
    'error-message',
    'user-comparator',
    'user',
    'custom-attribute-name',
    'custom-attribute-value-comparator',
    'custom-attribute-value',
    'limit',
  ]
  return keys.map((k) => `${k}=${params.get(k) ?? ''}`).join('&')
}

export function TransactionTracesPage({
  traceKind = 'transaction',
}: {
  traceKind?: 'transaction' | 'error'
}) {
  const txn = useTransaction()
  const { setDisplayedTraceCount } = txn
  const { layout } = useLayout()
  const { agentRollup, agentRollupId } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()

  const filterUrlKey = getFilterUrlKey(searchParams)
  const initialFilter = useMemo(() => parseFilterFromParams(searchParams), [filterUrlKey])

  const [draft, setDraft] = useState<TraceFilterCriteria>(initialFilter)
  const [applied, setApplied] = useState<TraceFilterCriteria>(initialFilter)
  const [manualRefresh, setManualRefresh] = useState(0)
  const [filterError, setFilterError] = useState<string | null>(null)

  useEffect(() => {
    setDraft(initialFilter)
    setApplied(initialFilter)
  }, [initialFilter])

  // Chart data
  const [normalPoints, setNormalPoints] = useState<TracePoint[]>([])
  const [errorPoints, setErrorPoints] = useState<TracePoint[]>([])
  const [partialPoints, setPartialPoints] = useState<TracePoint[]>([])
  const [loading, setLoading] = useState(false)
  const [hasLoaded, setHasLoaded] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [chartNoData, setChartNoData] = useState(false)
  const [expired, setExpired] = useState(false)
  const [limitExceeded, setLimitExceeded] = useState(false)

  const fetchIdRef = useRef(0)
  const showErrorMessageFilter = traceKind === 'error'
  const traceAttributeNames = agentRollup?.traceAttributeNames?.[txn.transactionType] ?? []

  const parsedDurationLow = useMemo(() => {
    if (applied.durationComparator === 'less' || !applied.durationLow.trim()) return 0
    const n = Number(applied.durationLow)
    return Number.isFinite(n) && n >= 0 ? n : 0
  }, [applied.durationComparator, applied.durationLow])

  const parsedDurationHigh = useMemo(() => {
    if (applied.durationComparator === 'greater' || !applied.durationHigh.trim()) return undefined
    const n = Number(applied.durationHigh)
    return Number.isFinite(n) && n >= 0 ? n : undefined
  }, [applied.durationComparator, applied.durationHigh])

  const fetchPoints = useCallback(() => {
    if (agentRollupId === null || !txn.transactionType) return
    const fetchId = ++fetchIdRef.current
    setLoading(true)
    setError(null)

    const query: Record<string, string | number | undefined> = {
      'transaction-type': txn.transactionType,
      from: txn.range.chartFrom,
      to: txn.range.chartTo,
      'duration-millis-low': parsedDurationLow,
      'headline-comparator': applied.headlineComparator,
      headline: applied.headline,
      'error-message-comparator': applied.errorMessageComparator,
      'error-message': applied.errorMessage,
      'user-comparator': applied.userComparator,
      user: applied.user,
      'attribute-name': applied.attributeName,
      'attribute-value-comparator': applied.attributeValueComparator,
      'attribute-value': applied.attributeValue,
      limit: applied.limit,
    }
    if (txn.transactionName) {
      query['transaction-name'] = txn.transactionName
    }
    if (parsedDurationHigh !== undefined) {
      query['duration-millis-high'] = parsedDurationHigh
    }

    const endpoint =
      traceKind === 'error' ? '/backend/error/points' : '/backend/transaction/points'
    apiGet<PointsResponse>(
      `${endpoint}${encodeObject(query)}&agent-rollup-id=${encodeURIComponent(agentRollupId)}`
    )
      .then((data) => {
        if (fetchId !== fetchIdRef.current) return
        const np = toTracePoints(data.normalPoints, 'normal')
        const ep = toTracePoints(data.errorPoints, 'error')
        const pp = toTracePoints(data.partialPoints, 'partial')
        setNormalPoints(np)
        setErrorPoints(ep)
        setPartialPoints(pp)
        const total = np.length + ep.length + pp.length
        setDisplayedTraceCount(total)
        setChartNoData(total === 0)
        setExpired(!!data.expired)
        setLimitExceeded(!!data.limitExceeded)
        setHasLoaded(true)
      })
      .catch((e) => {
        if (fetchId !== fetchIdRef.current) return
        setError(e)
      })
      .finally(() => {
        if (fetchId === fetchIdRef.current) setLoading(false)
      })
  }, [
    agentRollupId,
    txn.transactionType,
    txn.transactionName,
    txn.range.chartFrom,
    txn.range.chartTo,
    parsedDurationLow,
    parsedDurationHigh,
    applied,
    traceKind,
  ])

  useEffect(() => {
    fetchPoints()
  }, [fetchPoints, txn.refreshCounter, manualRefresh])

  useEffect(() => {
    return () => setDisplayedTraceCount(undefined)
  }, [setDisplayedTraceCount])

  const prevRangeLastRef = useRef(txn.range.last)
  useEffect(() => {
    if (txn.range.last > 0 && prevRangeLastRef.current !== txn.range.last) {
      prevRangeLastRef.current = txn.range.last
      if (
        applied.durationLow ||
        applied.durationHigh ||
        draft.durationLow ||
        draft.durationHigh
      ) {
        const cleared: TraceFilterCriteria = {
          ...applied,
          durationComparator: 'greater',
          durationLow: '',
          durationHigh: '',
        }
        setDraft((prev) => ({
          ...prev,
          durationComparator: 'greater',
          durationLow: '',
          durationHigh: '',
        }))
        setApplied(cleared)
        syncFilterToUrl(cleared)
      }
    } else {
      prevRangeLastRef.current = txn.range.last
    }
  }, [txn.range.last]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (txn.range.last <= 0 || layout.offlineViewer) return
    const timer = setInterval(() => {
      txn.rangeActions.refresh()
    }, 60_000)
    return () => clearInterval(timer)
  }, [txn.range.last, layout.offlineViewer, txn.rangeActions])

  function syncFilterToUrl(criteria: TraceFilterCriteria) {
    setSearchParams(() => {
      // Read live window.location.search so same-tick updates from useChartRange (from/to/last) are preserved
      const next = new URLSearchParams(window.location.search)
      if (criteria.durationComparator !== 'less' && criteria.durationLow.trim() !== '') {
        next.set('duration-millis-low', criteria.durationLow.trim())
      } else {
        next.delete('duration-millis-low')
      }
      if (criteria.durationComparator !== 'greater' && criteria.durationHigh.trim() !== '') {
        next.set('duration-millis-high', criteria.durationHigh.trim())
      } else {
        next.delete('duration-millis-high')
      }
      if (criteria.headline) {
        next.set('headline-comparator', criteria.headlineComparator)
        next.set('headline', criteria.headline)
      } else {
        next.delete('headline-comparator')
        next.delete('headline')
      }
      if (showErrorMessageFilter && criteria.errorMessage) {
        next.set('error-message-comparator', criteria.errorMessageComparator)
        next.set('error-message', criteria.errorMessage)
      } else {
        next.delete('error-message-comparator')
        next.delete('error-message')
      }
      if (criteria.user) {
        next.set('user-comparator', criteria.userComparator)
        next.set('user', criteria.user)
      } else {
        next.delete('user-comparator')
        next.delete('user')
      }
      if (criteria.attributeName) {
        next.set('custom-attribute-name', criteria.attributeName)
      } else {
        next.delete('custom-attribute-name')
      }
      if (criteria.attributeValue) {
        next.set('custom-attribute-value-comparator', criteria.attributeValueComparator)
        next.set('custom-attribute-value', criteria.attributeValue)
      } else {
        next.delete('custom-attribute-value-comparator')
        next.delete('custom-attribute-value')
      }
      if (criteria.limit !== 500) {
        next.set('limit', String(criteria.limit))
      } else {
        next.delete('limit')
      }
      return next
    })
  }

  function applyFilters() {
    const checkLow = draft.durationComparator !== 'less' ? draft.durationLow.trim() : ''
    const checkHigh = draft.durationComparator !== 'greater' ? draft.durationHigh.trim() : ''
    const numPattern = /^\d+(\.\d+)?$/
    if (
      (checkLow !== '' && !numPattern.test(checkLow)) ||
      (checkHigh !== '' && !numPattern.test(checkHigh))
    ) {
      setFilterError('Fix the invalid filter criteria')
      return
    }
    if (
      draft.durationComparator === 'between' &&
      checkLow !== '' &&
      checkHigh !== '' &&
      Number(checkLow) > Number(checkHigh)
    ) {
      setFilterError('Fix the invalid filter criteria')
      return
    }
    setFilterError(null)
    setApplied(draft)
    syncFilterToUrl(draft)
    setManualRefresh((c) => c + 1)
  }

  function clearFilters() {
    setFilterError(null)
    setDraft(DEFAULT_FILTER)
    setApplied(DEFAULT_FILTER)
    syncFilterToUrl(DEFAULT_FILTER)
    setManualRefresh((c) => c + 1)
  }

  const [modalSearchParams, setModalSearchParams] = useSearchParams()
  const modalAgentId = modalSearchParams.get('modal-agent-id') ?? ''
  const modalTraceId = modalSearchParams.get('modal-trace-id')
  const modalCheckLiveTraces = modalSearchParams.get('modal-check-live-traces') === 'true'
  const [highlightedTraceId, setHighlightedTraceId] = useState<string | null>(modalTraceId)

  useEffect(() => {
    setHighlightedTraceId(modalTraceId)
  }, [modalTraceId])

  function handleChartSelection(from: number, to: number, selLow?: number, selHigh?: number) {
    txn.rangeActions.handleSelection(from, to)
    if (selLow !== undefined) {
      const durationComparator: 'greater' | 'less' | 'between' =
        selHigh !== undefined && selLow > 0
          ? 'between'
          : selHigh !== undefined
            ? 'less'
            : 'greater'
      const durationLow = selHigh !== undefined && selLow === 0 ? '' : String(selLow)
      const durationHigh = selHigh !== undefined ? String(selHigh) : ''
      const nextCriteria: TraceFilterCriteria = {
        ...applied,
        durationComparator,
        durationLow,
        durationHigh,
      }
      setDraft(nextCriteria)
      setApplied(nextCriteria)
      syncFilterToUrl(nextCriteria)
    }
  }

  function handleChartZoom(from: number, to: number, zoomingOut: boolean) {
    txn.rangeActions.handleZoom(from, to, zoomingOut)
    if (zoomingOut) {
      const reset: TraceFilterCriteria = {
        ...applied,
        durationComparator: 'greater',
        durationLow: '',
        durationHigh: '',
      }
      setDraft(reset)
      setApplied(reset)
      syncFilterToUrl(reset)
    }
  }

  function handlePointClick(point: TracePoint, ctrlKey: boolean) {
    if (ctrlKey) {
      const section = window.location.pathname.includes('/error/') ? 'error' : 'transaction'
      const url = new URL(`${window.location.origin}${getContextPath()}/modern/${section}/traces`)
      modalSearchParams.forEach((value, key) => {
        url.searchParams.append(key, value)
      })
      url.searchParams.set('modal-agent-id', point.agentId)
      url.searchParams.set('modal-trace-id', point.traceId)
      if (point.checkLiveTraces) {
        url.searchParams.set('modal-check-live-traces', 'true')
      } else {
        url.searchParams.delete('modal-check-live-traces')
      }
      window.open(url.toString(), '_blank')
      return
    }
    setHighlightedTraceId(point.traceId)
    setModalSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.set('modal-agent-id', point.agentId)
      next.set('modal-trace-id', point.traceId)
      if (point.checkLiveTraces) {
        next.set('modal-check-live-traces', 'true')
      } else {
        next.delete('modal-check-live-traces')
      }
      return next
    })
  }

  if (loading && !hasLoaded) {
    return <PageSpinner />
  }
  if (error && !hasLoaded) return <HttpError error={error} />

  return (
    <div className="space-y-4">
      {error != null && hasLoaded && <HttpError error={error} />}

      {/* Chart */}
      <div className="rounded-lg border bg-white p-4 relative">
        {chartNoData && !loading ? (
          <div className="flex items-center justify-center h-[300px] text-sm text-gray-500">
            {expired ? 'Data has expired' : 'No traces for selected time range and filters'}
          </div>
        ) : (
          <TracePointChart
            normalPoints={normalPoints}
            errorPoints={errorPoints}
            partialPoints={partialPoints}
            chartFrom={txn.range.chartFrom}
            chartTo={txn.range.chartTo}
            durationLow={parsedDurationLow}
            durationHigh={parsedDurationHigh}
            highlightedTraceId={highlightedTraceId}
            onZoom={handleChartZoom}
            onSelection={handleChartSelection}
            onPointClick={handlePointClick}
          />
        )}
        {limitExceeded && (
          <div className="mt-2 text-xs text-orange-600">
            Limit of {applied.limit.toLocaleString()} trace points exceeded
          </div>
        )}
        {expired && !chartNoData && (
          <div className="mt-2 text-xs text-amber-700">
            Data has expired; try selecting a more recent time period
          </div>
        )}
      </div>

      {/* Filter form */}
      <form
        noValidate
        className="rounded-lg border bg-white p-4 space-y-3"
        onSubmit={(e) => {
          e.preventDefault()
          applyFilters()
        }}
      >
        <h3 className="text-sm font-medium text-gray-700">Filters</h3>

        {/* Duration */}
        <div className="flex items-center gap-2 text-sm flex-wrap">
          <label className="text-gray-500 w-28">Response time:</label>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={draft.durationComparator}
            onChange={(e) => {
              const nextComp = e.target.value as 'greater' | 'less' | 'between'
              setFilterError(null)
              setDraft((prev) => ({
                ...prev,
                durationComparator: nextComp,
                durationHigh: nextComp === 'greater' ? '' : prev.durationHigh,
                durationLow: nextComp === 'less' ? '' : prev.durationLow,
              }))
            }}
          >
            {DURATION_COMPARATORS.map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>
          {(draft.durationComparator === 'greater' || draft.durationComparator === 'between') && (
            <input
              id="filterDurationMillisLow"
              type="text"
              inputMode="decimal"
              className="rounded border px-2 py-1 text-sm w-24"
              placeholder="ms"
              value={draft.durationLow}
              onChange={(e) => {
                setFilterError(null)
                setDraft((prev) => ({ ...prev, durationLow: e.target.value }))
              }}
            />
          )}
          {draft.durationComparator === 'between' && <span className="text-gray-400">and</span>}
          {(draft.durationComparator === 'less' || draft.durationComparator === 'between') && (
            <input
              id="filterDurationMillisHigh"
              type="text"
              inputMode="decimal"
              className="rounded border px-2 py-1 text-sm w-24"
              placeholder="ms"
              value={draft.durationHigh}
              onChange={(e) => {
                setFilterError(null)
                setDraft((prev) => ({ ...prev, durationHigh: e.target.value }))
              }}
            />
          )}
          <span className="text-gray-400 text-xs">milliseconds</span>
        </div>

        {/* Headline */}
        <div className="flex items-center gap-2 text-sm flex-wrap">
          <label className="text-gray-500 w-28" htmlFor="searchHeadline">
            Headline/URL:
          </label>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={draft.headlineComparator}
            onChange={(e) =>
              setDraft((prev) => ({ ...prev, headlineComparator: e.target.value }))
            }
          >
            {TEXT_COMPARATORS.map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>
          <input
            id="searchHeadline"
            className="rounded border px-2 py-1 text-sm flex-1 min-w-[120px]"
            value={draft.headline}
            onChange={(e) => setDraft((prev) => ({ ...prev, headline: e.target.value }))}
          />
        </div>

        {/* Error message (only for error traces) */}
        {showErrorMessageFilter && (
          <div className="flex items-center gap-2 text-sm flex-wrap">
            <label className="text-gray-500 w-28" htmlFor="searchErrorMessage">
              Error:
            </label>
            <select
              className="rounded border px-2 py-1 text-sm"
              value={draft.errorMessageComparator}
              onChange={(e) =>
                setDraft((prev) => ({ ...prev, errorMessageComparator: e.target.value }))
              }
            >
              {TEXT_COMPARATORS.map((c) => (
                <option key={c.value} value={c.value}>
                  {c.label}
                </option>
              ))}
            </select>
            <input
              id="searchErrorMessage"
              className="rounded border px-2 py-1 text-sm flex-1 min-w-[120px]"
              value={draft.errorMessage}
              onChange={(e) => setDraft((prev) => ({ ...prev, errorMessage: e.target.value }))}
            />
          </div>
        )}

        {/* User */}
        <div className="flex items-center gap-2 text-sm flex-wrap">
          <label className="text-gray-500 w-28" htmlFor="searchUser">
            User:
          </label>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={draft.userComparator}
            onChange={(e) => setDraft((prev) => ({ ...prev, userComparator: e.target.value }))}
          >
            {TEXT_COMPARATORS.map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>
          <input
            id="searchUser"
            className="rounded border px-2 py-1 text-sm flex-1 min-w-[120px]"
            value={draft.user}
            onChange={(e) => setDraft((prev) => ({ ...prev, user: e.target.value }))}
          />
        </div>

        {/* Custom attributes */}
        {(traceAttributeNames.length > 0 || draft.attributeName || draft.attributeValue) && (
          <>
            <div className="flex items-center gap-2 text-sm flex-wrap">
              <label className="text-gray-500 w-28" htmlFor="searchAttributeName">
                Attribute name:
              </label>
              <select
                id="searchAttributeName"
                className="rounded border px-2 py-1 text-sm min-w-[180px]"
                value={draft.attributeName}
                onChange={(e) =>
                  setDraft((prev) => ({ ...prev, attributeName: e.target.value }))
                }
              >
                <option value=""></option>
                {draft.attributeName && !traceAttributeNames.includes(draft.attributeName) && (
                  <option value={draft.attributeName}>{draft.attributeName}</option>
                )}
                {traceAttributeNames.map((name) => (
                  <option key={name} value={name}>
                    {name}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex items-center gap-2 text-sm flex-wrap">
              <label className="text-gray-500 w-28" htmlFor="searchAttributeValue">
                Attribute value:
              </label>
              <select
                className="rounded border px-2 py-1 text-sm"
                value={draft.attributeValueComparator}
                onChange={(e) =>
                  setDraft((prev) => ({
                    ...prev,
                    attributeValueComparator: e.target.value,
                  }))
                }
              >
                {TEXT_COMPARATORS.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
              <input
                id="searchAttributeValue"
                className="rounded border px-2 py-1 text-sm flex-1 min-w-[120px]"
                value={draft.attributeValue}
                onChange={(e) =>
                  setDraft((prev) => ({ ...prev, attributeValue: e.target.value }))
                }
              />
            </div>
          </>
        )}

        {/* Limit */}
        <div className="flex items-center gap-2 text-sm">
          <label className="text-gray-500 w-28" htmlFor="searchLimit">
            Result limit:
          </label>
          <select
            id="searchLimit"
            className="rounded border px-2 py-1 text-sm"
            value={draft.limit}
            onChange={(e) =>
              setDraft((prev) => ({ ...prev, limit: Number(e.target.value) || 500 }))
            }
          >
            {LIMIT_OPTIONS.map((l) => (
              <option key={l} value={l}>
                {l.toLocaleString()}
              </option>
            ))}
          </select>
        </div>

        {filterError && (
          <div className="text-sm text-red-600">{filterError}</div>
        )}

        {/* Actions */}
        <div className="flex gap-2 pt-1">
          <button
            type="submit"
            className="rounded bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-700"
          >
            Refresh
          </button>
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm text-gray-600 hover:bg-gray-50"
            onClick={clearFilters}
          >
            Clear criteria
          </button>
        </div>
      </form>

      <TraceModal
        agentId={modalAgentId || agentRollupId || ''}
        traceId={modalTraceId}
        checkLiveTraces={modalCheckLiveTraces}
        onClose={() => {
          setModalSearchParams((prev) => {
            const next = new URLSearchParams(prev)
            next.delete('modal-agent-id')
            next.delete('modal-trace-id')
            next.delete('modal-check-live-traces')
            return next
          })
        }}
      />
    </div>
  )
}
