/*
 * TransactionTracesPage — scatter plot of trace points with filter form and URL sync.
 *
 * Backend: GET /backend/{transaction|error}/points
 */

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ComponentProps,
  type ReactNode,
} from 'react'
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
import { Button } from '../../components/ui/button'
import { Input } from '../../components/ui/input'
import { Select } from '../../components/ui/select'
import { cn } from '../../lib/utils'

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

/** Underline-only controls — no boxed borders */
const filterControlClass =
  'h-7 rounded-none border-0 border-b border-gray-300 bg-transparent px-1 text-xs shadow-none ' +
  'hover:border-gray-400 focus-visible:border-b-blue-500 focus-visible:ring-0 focus-visible:outline-none'

/** Native select: kill OS box; chevron via backgroundImage style (avoids twMerge bg-* clash) */
const filterSelectClass =
  filterControlClass + ' appearance-none cursor-pointer pr-5 bg-no-repeat'

const FILTER_SELECT_CHEVRON =
  "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='10' viewBox='0 0 24 24' fill='none' stroke='%236b7280' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E\")"

const filterSelectChevronStyle = {
  backgroundImage: FILTER_SELECT_CHEVRON,
  backgroundPosition: 'right 1px center',
  backgroundSize: '10px 10px',
  backgroundColor: 'transparent',
  backgroundRepeat: 'no-repeat',
} as const

function SlimFilterRow({
  label,
  htmlFor,
  children,
}: {
  label: string
  htmlFor?: string
  children: ReactNode
}) {
  return (
    <div className="grid grid-cols-[8.5rem_minmax(0,1fr)] items-center gap-x-3 py-1">
      <label htmlFor={htmlFor} className="text-right text-xs text-gray-600">
        {label}
      </label>
      <div className="min-w-0">{children}</div>
    </div>
  )
}

function FilterSelect({
  className,
  style,
  ...props
}: ComponentProps<typeof Select>) {
  return (
    <Select
      {...props}
      className={cn(filterSelectClass, className)}
      style={{ ...filterSelectChevronStyle, ...style }}
    />
  )
}

function TextComparatorField({
  id,
  comparator,
  onComparatorChange,
  value,
  onValueChange,
  comparatorAriaLabel,
  comparators = TEXT_COMPARATORS,
}: {
  id: string
  comparator: string
  onComparatorChange: (value: string) => void
  value: string
  onValueChange: (value: string) => void
  comparatorAriaLabel: string
  comparators?: Array<{ value: string; label: string }>
}) {
  return (
    <div className="flex max-w-[28em] items-center gap-1.5">
      <Select
        aria-label={comparatorAriaLabel}
        className={cn(filterControlClass, 'w-[9.5rem] shrink-0 rounded-l-md border-r-0')}
        value={comparator}
        onChange={(e) => onComparatorChange(e.target.value)}
      >
        {comparators.map((c) => (
          <option key={c.value} value={c.value}>
            {c.label}
          </option>
        ))}
      </FilterSelect>
      <Input
        id={id}
        className={cn(filterControlClass, 'min-w-0 flex-1')}
        value={value}
        onChange={(e) => onValueChange(e.target.value)}
        data-lpignore="true"
      />
    </div>
  )
}

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
  // Mirror classic traces.js: Number(x) || 0 / undefined — "0" is not a real bound
  const lowNum = Number(lowParam) || 0
  const highNum = highParam === '' ? undefined : Number(highParam) || undefined
  const durationComparator: 'greater' | 'less' | 'between' =
    lowNum !== 0 && highNum !== undefined
      ? 'between'
      : highNum !== undefined
        ? 'less'
        : 'greater'
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
      // Classic only writes duration params when Number(value) is truthy (so "0" is omitted)
      const low = criteria.durationLow.trim()
      const high = criteria.durationHigh.trim()
      if (criteria.durationComparator !== 'less' && low !== '' && Number(low)) {
        next.set('duration-millis-low', low)
      } else {
        next.delete('duration-millis-low')
      }
      if (criteria.durationComparator !== 'greater' && high !== '' && Number(high)) {
        next.set('duration-millis-high', high)
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

      {/* Filter form — slim controls, no fused boxes */}
      <form
        noValidate
        className="mt-3 space-y-0.5"
        onSubmit={(e) => {
          e.preventDefault()
          applyFilters()
        }}
      >
        <SlimFilterRow
          label="Response time"
          htmlFor={
            draft.durationComparator !== 'less'
              ? 'filterDurationMillisLow'
              : 'filterDurationMillisHigh'
          }
        >
          <div className="flex max-w-[32em] flex-wrap items-center gap-1.5">
            <FilterSelect
              aria-label="Response time comparator"
              className="w-[8.75rem] shrink-0"
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
            </FilterSelect>
            {(draft.durationComparator === 'greater' ||
              draft.durationComparator === 'between') && (
              <Input
                id="filterDurationMillisLow"
                type="text"
                inputMode="numeric"
                className={cn(filterControlClass, 'w-24')}
                value={draft.durationLow}
                onChange={(e) => {
                  setFilterError(null)
                  setDraft((prev) => ({ ...prev, durationLow: e.target.value }))
                }}
                data-lpignore="true"
              />
            )}
            {draft.durationComparator === 'between' && (
              <span className="text-xs text-gray-500">and</span>
            )}
            {(draft.durationComparator === 'less' ||
              draft.durationComparator === 'between') && (
              <Input
                id="filterDurationMillisHigh"
                type="text"
                inputMode="numeric"
                className={cn(filterControlClass, 'w-24')}
                value={draft.durationHigh}
                onChange={(e) => {
                  setFilterError(null)
                  setDraft((prev) => ({ ...prev, durationHigh: e.target.value }))
                }}
                data-lpignore="true"
              />
            )}
            <span className="text-xs text-gray-500">ms</span>
          </div>
        </SlimFilterRow>

        <SlimFilterRow label="Headline/URL" htmlFor="searchHeadline">
          <TextComparatorField
            id="searchHeadline"
            comparatorAriaLabel="Headline/URL comparator"
            comparator={draft.headlineComparator}
            onComparatorChange={(headlineComparator) =>
              setDraft((prev) => ({ ...prev, headlineComparator }))
            }
            value={draft.headline}
            onValueChange={(headline) => setDraft((prev) => ({ ...prev, headline }))}
          />
        </SlimFilterRow>

        {showErrorMessageFilter && (
          <SlimFilterRow label="Error" htmlFor="searchErrorMessage">
            <TextComparatorField
              id="searchErrorMessage"
              comparatorAriaLabel="Error message comparator"
              comparator={draft.errorMessageComparator}
              onComparatorChange={(errorMessageComparator) =>
                setDraft((prev) => ({ ...prev, errorMessageComparator }))
              }
              value={draft.errorMessage}
              onValueChange={(errorMessage) =>
                setDraft((prev) => ({ ...prev, errorMessage }))
              }
            />
          </SlimFilterRow>
        )}

        <SlimFilterRow label="User" htmlFor="searchUser">
          <TextComparatorField
            id="searchUser"
            comparatorAriaLabel="User comparator"
            comparator={draft.userComparator}
            onComparatorChange={(userComparator) =>
              setDraft((prev) => ({ ...prev, userComparator }))
            }
            value={draft.user}
            onValueChange={(user) => setDraft((prev) => ({ ...prev, user }))}
          />
        </SlimFilterRow>

        {(traceAttributeNames.length > 0 || draft.attributeName || draft.attributeValue) && (
          <>
            <SlimFilterRow label="Attribute name" htmlFor="searchAttributeName">
              <FilterSelect
                id="searchAttributeName"
                className="max-w-[28em]"
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
              </FilterSelect>
            </SlimFilterRow>
            <SlimFilterRow label="Attribute value" htmlFor="searchAttributeValue">
              <TextComparatorField
                id="searchAttributeValue"
                comparatorAriaLabel="Attribute value comparator"
                comparator={draft.attributeValueComparator}
                onComparatorChange={(attributeValueComparator) =>
                  setDraft((prev) => ({ ...prev, attributeValueComparator }))
                }
                value={draft.attributeValue}
                onValueChange={(attributeValue) =>
                  setDraft((prev) => ({ ...prev, attributeValue }))
                }
              />
            </SlimFilterRow>
          </>
        )}

        <SlimFilterRow label="Result limit" htmlFor="searchLimit">
          <FilterSelect
            id="searchLimit"
            className="w-24"
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
          </FilterSelect>
        </SlimFilterRow>

        {filterError && (
          <div className="grid grid-cols-3 gap-4">
            <div className="col-span-2 col-start-2 text-sm text-red-600" role="alert">
              {filterError}
            </div>
          </div>
        )}

        <div className="grid grid-cols-[8.5rem_minmax(0,1fr)] gap-x-3 pt-2">
          <div className="col-start-2 flex flex-wrap items-center gap-2">
            <Button type="submit" size="sm">
              Refresh
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={clearFilters}>
              Clear criteria
            </Button>
          </div>
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
