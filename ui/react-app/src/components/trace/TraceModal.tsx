/*
 * TraceModal — Full-viewport trace detail modal ported from trace-modal.js & handlebars-rendering.js.
 *
 * Backend endpoints:
 * - GET /backend/trace/header
 * - GET /backend/trace/entries
 * - GET /backend/trace/queries
 * - GET /backend/trace/main-thread-profile
 * - GET /backend/trace/aux-thread-profile
 * - GET /backend/transaction/full-query-text
 * - GET /export/trace (direct download)
 */

import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react'
import dayjs from 'dayjs'
import { apiGet, getContextPath } from '../../lib/api'
import { formatBytes, formatCount, formatMillis, formatPercent } from '../../lib/formatting'
import { PageSpinner } from '../shared/Spinner'

export interface TraceModalProps {
  agentId: string
  traceId: string | null
  checkLiveTraces?: boolean
  onClose: () => void
}

interface TraceTimer {
  name: string
  extended?: boolean
  totalNanos: number
  count: number
  active?: boolean
  childTimers?: TraceTimer[]
}

interface TraceThreadStats {
  totalCpuNanos?: number
  totalBlockedNanos?: number
  totalWaitedNanos?: number
  totalAllocatedBytes?: number
}

interface TraceThrowable {
  className: string
  message: string
  stackTraceElements: string[]
  framesInCommonWithEnclosing?: number
  cause?: TraceThrowable
}

interface TraceError {
  message: string
  exception?: TraceThrowable
}

interface TraceHeader {
  expired?: boolean
  agent?: string
  active?: boolean
  partial?: boolean
  async?: boolean
  startTime: number
  captureTime: number
  durationNanos: number
  transactionType: string
  transactionName: string
  headline: string
  user?: string
  attributes?: Record<string, string[]>
  detail?: Record<string, unknown>
  locationStackTraceElements?: string[]
  error?: TraceError
  mainThreadRootTimer: TraceTimer
  mainThreadStats?: TraceThreadStats
  auxThreadRootTimer?: TraceTimer
  auxThreadStats?: TraceThreadStats
  asyncTimers?: TraceTimer[]
  entryCount: number
  entryLimitExceeded?: boolean
  queryCount: number
  queryLimitExceeded?: boolean
  mainThreadProfileSampleCount: number
  mainThreadProfileSampleLimitExceeded?: boolean
  auxThreadProfileSampleCount: number
  auxThreadProfileSampleLimitExceeded?: boolean
  entriesExistence: 'yes' | 'no' | 'expired'
  queriesExistence: 'yes' | 'no' | 'expired'
  profileExistence: 'yes' | 'no' | 'expired'
}

interface SharedQueryText {
  fullText?: string
  truncatedText?: string
  truncatedEndText?: string
  fullTextSha1?: string
}

interface QueryEntryMessage {
  sharedQueryTextIndex: number
  prefix: string
  suffix: string
}

interface TraceEntry {
  startOffsetNanos: number
  durationNanos: number
  active?: boolean
  message?: string
  queryMessage?: QueryEntryMessage
  detail?: Record<string, unknown>
  locationStackTraceElements?: string[]
  error?: TraceError
  childEntries?: TraceEntry[]
}

interface TraceEntriesResponse {
  entries: TraceEntry[]
  sharedQueryTexts: SharedQueryText[]
}

interface TraceQuery {
  type: string
  sharedQueryTextIndex: number
  totalDurationNanos: number
  executionCount: number
  totalRows?: number
  active?: boolean
}

interface TraceQueriesResponse {
  queries: TraceQuery[]
  sharedQueryTexts: SharedQueryText[]
}

interface ProfileNode {
  stackTraceElement: string
  leafThreadState?: string
  sampleCount: number
  ellipsedSampleCount?: number
  childNodes?: ProfileNode[]
}

interface ProfileResponse {
  unfilteredSampleCount: number
  rootNodes: ProfileNode[]
}

function nanosToMillisStr(nanos: number | undefined): string {
  return formatMillis((nanos ?? 0) / 1e6)
}

function isDragSelection(): boolean {
  const sel = typeof window !== 'undefined' ? window.getSelection()?.toString() : ''
  return Boolean(sel && sel.length > 0)
}

function stripJdbcClipboardPrefix(text: string): string {
  for (const prefix of ['jdbc query:\n\n', 'jdbc execute:\n\n', 'jdbc execution:\n\n']) {
    if (text.startsWith(prefix)) {
      return text.slice(prefix.length) + '\n'
    }
  }
  return text
}

function CopyButton({ text, label = 'Copy to clipboard' }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <button
      type="button"
      title={copied ? 'Copied!' : label}
      aria-label={copied ? 'Copied!' : label}
      onClick={(e) => {
        e.stopPropagation()
        navigator.clipboard?.writeText(text).catch(() => {})
        setCopied(true)
        setTimeout(() => setCopied(false), 1000)
      }}
      className="ml-2 inline-flex items-center rounded border border-gray-300 bg-gray-50 px-1.5 py-0.5 text-[11px] font-sans text-gray-600 hover:bg-gray-100"
    >
      {copied ? 'Copied!' : 'Copy'}
    </button>
  )
}

function DetailTree({ detail, depth = 0 }: { detail: Record<string, unknown>; depth?: number }) {
  return (
    <div className={depth > 0 ? 'pl-4' : ''}>
      {Object.entries(detail).map(([key, value]) => {
        if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
          return (
            <div key={key} className="py-0.5">
              <div className="text-gray-600">{key}:</div>
              <DetailTree detail={value as Record<string, unknown>} depth={depth + 1} />
            </div>
          )
        }
        if (Array.isArray(value)) {
          return (
            <div key={key} className="py-0.5">
              <div className="text-gray-600">{key}:</div>
              <div className="pl-4">
                {value.map((item, idx) => (
                  <div key={idx} className="font-mono text-xs break-all">
                    {item === null ? 'null' : String(item)}
                  </div>
                ))}
              </div>
            </div>
          )
        }
        return (
          <div key={key} className="py-0.5 break-words">
            <span className="text-gray-600">{key}: </span>
            <span className="font-mono text-xs">{value === null ? 'null' : String(value)}</span>
          </div>
        )
      })}
    </div>
  )
}

function formatThrowablePlainText(t: TraceThrowable, isCause = false): string {
  const lines: string[] = []
  const prefix = isCause ? 'Caused by: ' : ''
  lines.push(`${prefix}${t.className}${t.message ? ': ' + t.message : ''}`)
  for (const el of t.stackTraceElements) {
    lines.push(`    at ${el}`)
  }
  if (t.framesInCommonWithEnclosing) {
    lines.push(`    ... ${t.framesInCommonWithEnclosing} more`)
  }
  if (t.cause) {
    lines.push(formatThrowablePlainText(t.cause, true))
  }
  return lines.join('\n')
}

function ThrowableChain({ throwable, isCause = false }: { throwable: TraceThrowable; isCause?: boolean }) {
  return (
    <div className="font-mono text-xs whitespace-pre-wrap break-all">
      <div>
        {isCause ? 'Caused by: ' : ''}
        <span className="font-semibold">{throwable.className}</span>
        {throwable.message ? `: ${throwable.message}` : ''}
      </div>
      {throwable.stackTraceElements.map((el, i) => (
        <div key={i} className="pl-6">
          at {el}
        </div>
      ))}
      {throwable.framesInCommonWithEnclosing ? (
        <div className="pl-6">... {throwable.framesInCommonWithEnclosing} more</div>
      ) : null}
      {throwable.cause && <ThrowableChain throwable={throwable.cause} isCause />}
    </div>
  )
}

interface FlatTraceTimer {
  name: string
  totalNanos: number
  count: number
  active?: boolean
}

interface TreeTraceTimer {
  name: string
  totalNanos: number
  count: number
  extended?: boolean
  active?: boolean
  depth: number
}

function flattenTraceTimers(rootTimer: TraceTimer): FlatTraceTimer[] {
  const map = new Map<string, FlatTraceTimer>()
  const list: FlatTraceTimer[] = []

  function visit(timer: TraceTimer, parents: string[]) {
    const existing = map.get(timer.name)
    if (!existing) {
      const created: FlatTraceTimer = {
        name: timer.name,
        totalNanos: timer.totalNanos,
        count: timer.extended ? 0 : timer.count,
        active: timer.active,
      }
      map.set(timer.name, created)
      list.push(created)
    } else if (!parents.includes(timer.name)) {
      existing.totalNanos += timer.totalNanos
      existing.active = existing.active || timer.active
      if (!timer.extended) {
        existing.count += timer.count
      }
    }
    if (timer.childTimers) {
      const nextParents = [...parents, timer.name]
      for (const child of timer.childTimers) {
        visit(child, nextParents)
      }
    }
  }

  visit(rootTimer, [])
  list.sort((a, b) => b.totalNanos - a.totalNanos)
  return list
}

function buildTraceTimerTree(rootTimer: TraceTimer): TreeTraceTimer[] {
  const list: TreeTraceTimer[] = []
  function visit(timer: TraceTimer, depth: number) {
    list.push({
      name: timer.name,
      totalNanos: timer.totalNanos,
      count: timer.count,
      extended: timer.extended,
      active: timer.active,
      depth,
    })
    if (timer.childTimers) {
      const sorted = [...timer.childTimers].sort((a, b) => b.totalNanos - a.totalNanos)
      for (const child of sorted) {
        visit(child, depth + 1)
      }
    }
  }
  visit(rootTimer, 0)
  return list
}

function TraceBreakdownSection({ title, rootTimer }: { title: string; rootTimer: TraceTimer }) {
  const [treeView, setTreeView] = useState(false)
  const [limit, setLimit] = useState(10)

  const flattened = useMemo(() => flattenTraceTimers(rootTimer), [rootTimer])
  const tree = useMemo(() => buildTraceTimerTree(rootTimer), [rootTimer])

  const activeList = treeView ? tree : flattened
  const totalCount = activeList.length
  const visibleCount = Number.isFinite(limit) ? Math.min(limit, totalCount) : totalCount
  const showMore = visibleCount < totalCount
  const showLess = visibleCount > 10

  return (
    <div className="space-y-1">
      <div className="flex items-center gap-3 text-sm">
        <span className="font-semibold text-gray-800">{title}</span>
        <button
          type="button"
          onClick={() => setTreeView((v) => !v)}
          className="text-xs text-blue-600 hover:underline"
        >
          {treeView ? 'switch to flattened view' : 'switch to tree view'}
        </button>
      </div>
      <div className="overflow-x-auto">
        <table className="w-auto text-xs border-collapse">
          <thead>
            <tr className="border-b text-gray-500 text-left">
              <th className="py-1 pr-6 font-medium">Timer</th>
              <th className="py-1 pr-6 font-medium text-right">Total (ms)</th>
              <th className="py-1 pr-4 font-medium text-right">Count</th>
            </tr>
          </thead>
          <tbody>
            {treeView
              ? tree.slice(0, visibleCount).map((row, idx) => (
                  <tr key={idx} className="border-b border-gray-100">
                    <td
                      className="py-0.5 pr-6 text-gray-800"
                      style={{ paddingLeft: row.depth * 16.82 }}
                    >
                      {row.name}
                      {row.extended ? ' (extended)' : ''}
                    </td>
                    <td className="py-0.5 pr-6 text-right tabular-nums">
                      {nanosToMillisStr(row.totalNanos)}
                      {row.active ? '..' : ''}
                    </td>
                    <td className="py-0.5 pr-4 text-right tabular-nums">
                      {row.count.toLocaleString()}
                    </td>
                  </tr>
                ))
              : flattened.slice(0, visibleCount).map((row) => (
                  <tr key={row.name} className="border-b border-gray-100">
                    <td className="py-0.5 pr-6 text-gray-800">{row.name}</td>
                    <td className="py-0.5 pr-6 text-right tabular-nums">
                      {nanosToMillisStr(row.totalNanos)}
                      {row.active ? '..' : ''}
                    </td>
                    <td className="py-0.5 pr-4 text-right tabular-nums">
                      {row.count.toLocaleString()}
                    </td>
                  </tr>
                ))}
          </tbody>
        </table>
      </div>
      {(showMore || showLess) && (
        <div className="flex items-center gap-3 text-xs">
          {showLess && (
            <button
              type="button"
              onClick={() => setLimit((prev) => Math.max(10, Math.floor((Number.isFinite(prev) ? prev : totalCount) / 2)))}
              className="text-blue-600 hover:underline"
            >
              show less
            </button>
          )}
          {showMore && (
            <>
              <button
                type="button"
                onClick={() => setLimit((prev) => (Number.isFinite(prev) ? prev * 2 : 20))}
                className="text-blue-600 hover:underline"
              >
                show more
              </button>
              <button
                type="button"
                onClick={() => setLimit(Infinity)}
                className="text-blue-600 hover:underline"
              >
                show all
              </button>
            </>
          )}
        </div>
      )}
    </div>
  )
}

function hasAnyThreadStats(stats?: TraceThreadStats): boolean {
  if (!stats) return false
  return (
    (stats.totalCpuNanos !== undefined && stats.totalCpuNanos !== -1) ||
    (stats.totalBlockedNanos !== undefined && stats.totalBlockedNanos !== -1) ||
    (stats.totalWaitedNanos !== undefined && stats.totalWaitedNanos !== -1) ||
    (stats.totalAllocatedBytes !== undefined && stats.totalAllocatedBytes !== -1)
  )
}

function ThreadStatsSection({ title, stats }: { title: string; stats: TraceThreadStats }) {
  return (
    <div className="space-y-1 text-xs">
      <div className="text-sm font-semibold text-gray-800">{title}</div>
      <div className="pl-4 space-y-0.5">
        {stats.totalCpuNanos !== undefined && stats.totalCpuNanos !== -1 && (
          <div>
            <span className="text-gray-600">CPU time: </span>
            <span>{nanosToMillisStr(stats.totalCpuNanos)} milliseconds</span>
          </div>
        )}
        {stats.totalBlockedNanos !== undefined && stats.totalBlockedNanos !== -1 && (
          <div>
            <span className="text-gray-600">Blocked time: </span>
            <span>{nanosToMillisStr(stats.totalBlockedNanos)} milliseconds</span>
          </div>
        )}
        {stats.totalWaitedNanos !== undefined && stats.totalWaitedNanos !== -1 && (
          <div>
            <span className="text-gray-600">Waited time: </span>
            <span>{nanosToMillisStr(stats.totalWaitedNanos)} milliseconds</span>
          </div>
        )}
        {stats.totalAllocatedBytes !== undefined && stats.totalAllocatedBytes !== -1 && (
          <div>
            <span className="text-gray-600">Allocated memory: </span>
            <span>{formatBytes(stats.totalAllocatedBytes)}</span>
          </div>
        )}
      </div>
    </div>
  )
}

function AttrRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="text-xs leading-relaxed break-words">
      <span className="text-gray-600">{label}:&nbsp;</span>
      <span className="text-gray-900 whitespace-pre-wrap">{children}</span>
    </div>
  )
}

function DetailToggle({
  open,
  loading,
  onClick,
  children,
}: {
  open: boolean
  loading?: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={loading}
      aria-expanded={open}
      className="px-0 py-0.5 text-left text-sm text-blue-700 hover:underline disabled:opacity-50 bg-transparent border-0 cursor-pointer"
    >
      {children}
    </button>
  )
}

interface FlatEntryNode {
  path: string
  depth: number
  entry: TraceEntry
  hasChildren: boolean
}

function flattenVisibleEntries(
  entries: TraceEntry[],
  collapsedPaths: Set<string>,
  prefix = '',
  depth = 0
): FlatEntryNode[] {
  const out: FlatEntryNode[] = []
  entries.forEach((entry, i) => {
    const path = prefix ? `${prefix}.${i}` : `${i}`
    const hasChildren = Boolean(entry.childEntries && entry.childEntries.length > 0)
    out.push({ path, depth, entry, hasChildren })
    if (hasChildren && !collapsedPaths.has(path)) {
      out.push(...flattenVisibleEntries(entry.childEntries!, collapsedPaths, path, depth + 1))
    }
  })
  return out
}

function resolveMaxDurationNanos(entries: TraceEntry[], baseDurationNanos: number): number {
  let max = baseDurationNanos
  const stack = [...entries]
  while (stack.length > 0) {
    const e = stack.pop()!
    const end = e.startOffsetNanos + e.durationNanos
    if (end > max) max = end
    if (e.childEntries) {
      for (const c of e.childEntries) stack.push(c)
    }
  }
  return max
}

function TraceEntriesSection({
  agentId,
  traceId,
  checkLiveTraces,
  entryCount,
  baseDurationNanos,
  onDurationUpdate,
  sharedTextCache,
  onCacheFullText,
}: {
  agentId: string
  traceId: string
  checkLiveTraces?: boolean
  entryCount: number
  baseDurationNanos: number
  onDurationUpdate: (nanos: number) => void
  sharedTextCache: Record<string, { fullText?: string; expired?: boolean; error?: string }>
  onCacheFullText: (
    sha1: string,
    val: { fullText?: string; expired?: boolean; error?: string }
  ) => void
}) {
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [data, setData] = useState<TraceEntriesResponse | null>(null)
  const [collapsedPaths, setCollapsedPaths] = useState<Set<string>>(() => new Set())
  const [expandedMessages, setExpandedMessages] = useState<Set<string>>(() => new Set())
  const [expandedDetails, setExpandedDetails] = useState<Set<string>>(() => new Set())
  const [expandedErrors, setExpandedErrors] = useState<Set<string>>(() => new Set())
  const [expandedLocations, setExpandedLocations] = useState<Set<string>>(() => new Set())
  const [renderLimit, setRenderLimit] = useState(100)

  async function toggleOpen() {
    if (loading) return
    if (data) {
      setOpen((v) => !v)
      return
    }
    setLoading(true)
    setError(false)
    try {
      const resp = await apiGet<TraceEntriesResponse>('/backend/trace/entries', {
        'agent-id': agentId,
        'trace-id': traceId,
        ...(checkLiveTraces ? { 'check-live-traces': 'true' } : {}),
      })
      setData(resp)
      setOpen(true)
      const maxDur = resolveMaxDurationNanos(resp.entries, baseDurationNanos)
      if (maxDur > baseDurationNanos) {
        onDurationUpdate(maxDur)
      }
    } catch {
      setError(true)
      setOpen(true)
    } finally {
      setLoading(false)
    }
  }

  const visibleEntries = useMemo(() => {
    if (!data) return []
    return flattenVisibleEntries(data.entries, collapsedPaths)
  }, [data, collapsedPaths])

  useEffect(() => {
    if (!open || !data) return
    if (renderLimit >= visibleEntries.length) return
    const timer = setTimeout(() => {
      setRenderLimit((prev) => prev + 500)
    }, 10)
    return () => clearTimeout(timer)
  }, [open, data, renderLimit, visibleEntries.length])

  async function ensureFullQueryText(sha1: string) {
    if (sharedTextCache[sha1]) return
    try {
      const resp = await apiGet<{ fullText?: string; expired?: boolean }>(
        '/backend/transaction/full-query-text',
        {
          'agent-rollup-id': agentId,
          'full-text-sha1': sha1,
        }
      )
      if (resp.expired) {
        onCacheFullText(sha1, { expired: true })
      } else {
        onCacheFullText(sha1, { fullText: resp.fullText })
      }
    } catch {
      onCacheFullText(sha1, { error: 'An error occurred retrieving the full query text' })
    }
  }

  function toggleSetItem(
    setter: React.Dispatch<React.SetStateAction<Set<string>>>,
    key: string
  ) {
    setter((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const totalDuration = Math.max(1, baseDurationNanos)

  return (
    <div className="space-y-2">
      <DetailToggle open={open} loading={loading} onClick={toggleOpen}>
        Trace entries ({entryCount.toLocaleString()})
      </DetailToggle>
      {loading && <PageSpinner />}
      {error && (
        <div className="text-sm text-red-600">An error occurred retrieving the trace entries</div>
      )}
      {open && data && (
        <div className="ml-4 space-y-0.5 font-mono text-xs overflow-x-auto">
          {visibleEntries.slice(0, renderLimit).map(({ path, depth, entry, hasChildren }) => {
            const leftPct = Math.max(
              0,
              Math.min(100, (entry.startOffsetNanos / totalDuration) * 100)
            )
            const widthPct = Math.max(
              0.5,
              Math.min(100 - leftPct, (entry.durationNanos / totalDuration) * 100)
            )
            const barTooltip = `duration ${nanosToMillisStr(entry.durationNanos)}${entry.active ? '..' : ''} ms, offset ${nanosToMillisStr(entry.startOffsetNanos)} ms`

            const qm = entry.queryMessage
            const shared = qm ? data.sharedQueryTexts[qm.sharedQueryTextIndex] : undefined
            const sha1 = shared?.fullTextSha1
            const cached = sha1 ? sharedTextCache[sha1] : undefined
            const resolvedFullText = shared?.fullText ?? cached?.fullText
            const fullText = qm
              ? resolvedFullText !== undefined
                ? `${qm.prefix}${resolvedFullText}${qm.suffix}`
                : undefined
              : entry.message
            const truncatedPreview = qm
              ? shared?.truncatedText !== undefined
                ? `${qm.prefix}${shared.truncatedText} ... ${shared.truncatedEndText ?? ''}${qm.suffix}`
                : ''
              : entry.message && entry.message.length > 140
                ? `${entry.message.slice(0, 70)} ... ${entry.message.slice(-60)}`
                : undefined

            const isExpandedMsg = expandedMessages.has(path)
            const displayMessage =
              !isExpandedMsg && truncatedPreview
                ? truncatedPreview
                : fullText ?? truncatedPreview ?? ''
            const canExpandMsg = Boolean(truncatedPreview)

            const showMsg = Boolean(entry.message || entry.queryMessage || !entry.error)
            const isCollapsed = collapsedPaths.has(path)

            return (
              <div key={path} className="flex items-start gap-2 py-0.5 hover:bg-gray-50">
                <div
                  title={barTooltip}
                  className="relative w-[102px] h-4 shrink-0 bg-gray-100 border border-gray-200 mt-0.5"
                >
                  <div
                    className="gt-trace-entry-bar absolute top-0 bottom-0 bg-amber-300 border border-amber-500"
                    style={{ left: `${leftPct}%`, width: `${widthPct}%` }}
                  />
                </div>
                <div className="flex-1 min-w-0" style={{ paddingLeft: depth * 16 }}>
                  <div className="flex items-start gap-1">
                    {hasChildren ? (
                      <button
                        type="button"
                        title={isCollapsed ? 'Expand' : 'Collapse'}
                        aria-label={isCollapsed ? 'Expand' : 'Collapse'}
                        onClick={() => toggleSetItem(setCollapsedPaths, path)}
                        className="w-4 h-4 shrink-0 border border-gray-400 text-[10px] leading-none flex items-center justify-center bg-white text-gray-700 mt-0.5"
                      >
                        {isCollapsed ? '+' : '-'}
                      </button>
                    ) : (
                      <span className="w-4 shrink-0" />
                    )}
                    <div className="flex-1 min-w-0 break-all">
                      {showMsg && (
                        <div>
                          <span
                            className={canExpandMsg ? 'cursor-pointer hover:underline' : ''}
                            onClick={() => {
                              if (isDragSelection() || !canExpandMsg) return
                              if (!isExpandedMsg && sha1) {
                                ensureFullQueryText(sha1)
                              }
                              toggleSetItem(setExpandedMessages, path)
                            }}
                          >
                            {displayMessage}
                          </span>
                          {isExpandedMsg && cached?.expired && (
                            <span className="ml-2 text-gray-500">
                              [the full query text has expired]
                            </span>
                          )}
                          {isExpandedMsg && cached?.error && (
                            <span className="ml-2 text-red-600">{cached.error}</span>
                          )}
                          {(isExpandedMsg || !canExpandMsg) && fullText && (
                            <CopyButton text={stripJdbcClipboardPrefix(fullText)} />
                          )}
                        </div>
                      )}
                      {entry.detail && Object.keys(entry.detail).length > 0 && (
                        <div className="mt-0.5">
                          <button
                            type="button"
                            onClick={() => toggleSetItem(setExpandedDetails, path)}
                            className="text-blue-600 hover:underline font-sans"
                          >
                            {expandedDetails.has(path) ? 'Hide detail' : 'detail'}
                          </button>
                          {expandedDetails.has(path) && (
                            <div className="mt-1 pl-2 border-l border-gray-200 font-sans">
                              <DetailTree detail={entry.detail} />
                            </div>
                          )}
                        </div>
                      )}
                      {entry.error && (
                        <div className="mt-0.5 text-red-700">
                          <div>{entry.error.message}</div>
                          {entry.error.exception && (
                            <div>
                              <button
                                type="button"
                                onClick={() => toggleSetItem(setExpandedErrors, path)}
                                className="text-blue-600 hover:underline font-sans"
                              >
                                {expandedErrors.has(path)
                                  ? 'Hide stack trace'
                                  : `${entry.error.exception.className}${entry.error.exception.message ? ': ' + entry.error.exception.message : ''}`}
                              </button>
                              <CopyButton text={formatThrowablePlainText(entry.error.exception)} />
                              {expandedErrors.has(path) && (
                                <div className="mt-1 pl-2 border-l border-red-200">
                                  <ThrowableChain throwable={entry.error.exception} />
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      )}
                      {entry.locationStackTraceElements &&
                        entry.locationStackTraceElements.length > 0 && (
                          <div className="mt-0.5 text-gray-600">
                            <button
                              type="button"
                              onClick={() => toggleSetItem(setExpandedLocations, path)}
                              className="text-blue-600 hover:underline font-sans"
                            >
                              {expandedLocations.has(path)
                                ? 'Hide location stack trace'
                                : `Location stack trace: ${entry.locationStackTraceElements[0]}`}
                            </button>
                            <CopyButton text={entry.locationStackTraceElements.join('\n')} />
                            {expandedLocations.has(path) && (
                              <div className="mt-1 pl-4 whitespace-pre-wrap">
                                {entry.locationStackTraceElements.join('\n')}
                              </div>
                            )}
                          </div>
                        )}
                    </div>
                  </div>
                </div>
              </div>
            )
          })}
          {renderLimit < visibleEntries.length && (
            <div className="py-1 text-gray-500">
              Rendering {renderLimit} of {visibleEntries.length} entries...
            </div>
          )}
        </div>
      )}
    </div>
  )
}

type QuerySortColumn = 'totalDurationNanos' | 'executionCount' | 'timePerExecution' | 'rowsPerExecution'

function TraceQueriesSection({
  agentId,
  traceId,
  checkLiveTraces,
  queryCount,
  sharedTextCache,
  onCacheFullText,
}: {
  agentId: string
  traceId: string
  checkLiveTraces?: boolean
  queryCount: number
  sharedTextCache: Record<string, { fullText?: string; expired?: boolean; error?: string }>
  onCacheFullText: (
    sha1: string,
    val: { fullText?: string; expired?: boolean; error?: string }
  ) => void
}) {
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [data, setData] = useState<TraceQueriesResponse | null>(null)
  const [sortCol, setSortCol] = useState<QuerySortColumn>('totalDurationNanos')
  const [sortAsc, setSortAsc] = useState(false)
  const [expandedRows, setExpandedRows] = useState<Set<number>>(() => new Set())
  const [renderLimit, setRenderLimit] = useState(100)

  async function toggleOpen() {
    if (loading) return
    if (data) {
      setOpen((v) => !v)
      return
    }
    setLoading(true)
    setError(false)
    try {
      const resp = await apiGet<TraceQueriesResponse>('/backend/trace/queries', {
        'agent-id': agentId,
        'trace-id': traceId,
        ...(checkLiveTraces ? { 'check-live-traces': 'true' } : {}),
      })
      setData(resp)
      setOpen(true)
    } catch {
      setError(true)
      setOpen(true)
    } finally {
      setLoading(false)
    }
  }

  function handleSort(col: QuerySortColumn) {
    if (sortCol === col) {
      setSortAsc((a) => !a)
    } else {
      setSortCol(col)
      setSortAsc(false)
    }
  }

  async function ensureFullQueryText(sha1: string) {
    if (sharedTextCache[sha1]) return
    try {
      const resp = await apiGet<{ fullText?: string; expired?: boolean }>(
        '/backend/transaction/full-query-text',
        {
          'agent-rollup-id': agentId,
          'full-text-sha1': sha1,
        }
      )
      if (resp.expired) {
        onCacheFullText(sha1, { expired: true })
      } else {
        onCacheFullText(sha1, { fullText: resp.fullText })
      }
    } catch {
      onCacheFullText(sha1, { error: 'An error occurred retrieving the full query text' })
    }
  }

  const sortedQueries = useMemo(() => {
    if (!data) return []
    const getSortVal = (q: TraceQuery): number | undefined => {
      switch (sortCol) {
        case 'totalDurationNanos':
          return q.totalDurationNanos
        case 'executionCount':
          return q.executionCount
        case 'timePerExecution':
          return q.executionCount > 0 ? q.totalDurationNanos / q.executionCount : 0
        case 'rowsPerExecution':
          return q.totalRows !== undefined && q.executionCount > 0
            ? q.totalRows / q.executionCount
            : undefined
      }
    }
    return data.queries
      .map((q, idx) => ({ q, idx }))
      .sort((a, b) => {
        const va = getSortVal(a.q)
        const vb = getSortVal(b.q)
        if (va === undefined && vb === undefined) return 0
        if (va === undefined) return 1
        if (vb === undefined) return -1
        return sortAsc ? va - vb : vb - va
      })
  }, [data, sortCol, sortAsc])

  useEffect(() => {
    setRenderLimit(100)
  }, [data, sortCol, sortAsc])

  useEffect(() => {
    if (!open || !data) return
    if (renderLimit >= sortedQueries.length) return
    const timer = setTimeout(() => {
      setRenderLimit((prev) => prev + 500)
    }, 10)
    return () => clearTimeout(timer)
  }, [open, data, renderLimit, sortedQueries.length])

  const sortArrow = (col: QuerySortColumn) =>
    sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : ''

  return (
    <div className="space-y-2">
      <DetailToggle open={open} loading={loading} onClick={toggleOpen}>
        Query stats ({queryCount.toLocaleString()})
      </DetailToggle>
      {loading && <PageSpinner />}
      {error && (
        <div className="text-sm text-red-600">
          An error occurred retrieving the trace query stats
        </div>
      )}
      {open && data && (
        <div className="ml-4 overflow-x-auto">
          <table className="w-full text-xs border-collapse">
            <thead>
              <tr className="border-b text-gray-600 text-left">
                <th className="py-1 pr-4 font-medium">Query</th>
                <th className="py-1 px-3 font-medium text-right whitespace-nowrap">
                  <button type="button" onClick={() => handleSort('totalDurationNanos')}>
                    Total time (ms){sortArrow('totalDurationNanos')}
                  </button>
                </th>
                <th className="py-1 px-3 font-medium text-right whitespace-nowrap">
                  <button type="button" onClick={() => handleSort('executionCount')}>
                    Execution count{sortArrow('executionCount')}
                  </button>
                </th>
                <th className="py-1 px-3 font-medium text-right whitespace-nowrap">
                  <button type="button" onClick={() => handleSort('timePerExecution')}>
                    Time per execution (ms){sortArrow('timePerExecution')}
                  </button>
                </th>
                <th className="py-1 pl-3 font-medium text-right whitespace-nowrap">
                  <button type="button" onClick={() => handleSort('rowsPerExecution')}>
                    Rows per execution{sortArrow('rowsPerExecution')}
                  </button>
                </th>
              </tr>
            </thead>
            <tbody>
              {sortedQueries.slice(0, renderLimit).map(({ q, idx }) => {
                const shared = data.sharedQueryTexts[q.sharedQueryTextIndex]
                const sha1 = shared?.fullTextSha1
                const cached = sha1 ? sharedTextCache[sha1] : undefined
                const fullText = shared?.fullText ?? cached?.fullText
                const preview =
                  shared?.truncatedText !== undefined
                    ? `${shared.truncatedText} ... ${shared.truncatedEndText ?? ''}`
                    : fullText && fullText.length > 140
                      ? `${fullText.slice(0, 70)} ... ${fullText.slice(-60)}`
                      : fullText ?? ''
                const canExpand = Boolean(
                  sha1 || (fullText && fullText.length > 140)
                )
                const expanded = expandedRows.has(idx)
                const rowsPerExec =
                  q.totalRows !== undefined && q.executionCount > 0
                    ? `${formatCount(q.totalRows / q.executionCount)}${q.active ? '..' : ''}`
                    : ''
                const timePerExec =
                  q.executionCount > 0 ? q.totalDurationNanos / q.executionCount : 0

                return (
                  <tr key={idx} className="border-b border-gray-100 align-top">
                    <td className="py-1 pr-4 font-mono break-all">
                      <span
                        className={canExpand ? 'cursor-pointer hover:underline' : ''}
                        onClick={() => {
                          if (isDragSelection() || !canExpand) return
                          if (!expanded && sha1) {
                            ensureFullQueryText(sha1)
                          }
                          setExpandedRows((prev) => {
                            const next = new Set(prev)
                            if (next.has(idx)) next.delete(idx)
                            else next.add(idx)
                            return next
                          })
                        }}
                      >
                        {expanded && fullText !== undefined ? fullText : preview}
                      </span>
                      {expanded && cached?.expired && (
                        <span className="ml-2 text-gray-500">
                          [the full query text has expired]
                        </span>
                      )}
                      {expanded && cached?.error && (
                        <span className="ml-2 text-red-600">{cached.error}</span>
                      )}
                    </td>
                    <td className="py-1 px-3 text-right tabular-nums whitespace-nowrap">
                      {nanosToMillisStr(q.totalDurationNanos)}
                      {q.active ? '..' : ''}
                    </td>
                    <td className="py-1 px-3 text-right tabular-nums whitespace-nowrap">
                      {q.executionCount.toLocaleString()}
                    </td>
                    <td className="py-1 px-3 text-right tabular-nums whitespace-nowrap">
                      {nanosToMillisStr(timePerExec)}
                      {q.active ? '..' : ''}
                    </td>
                    <td className="py-1 pl-3 text-right tabular-nums whitespace-nowrap">
                      {rowsPerExec}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function buildContractedRoot(profile: ProfileResponse): ProfileNode {
  if (profile.rootNodes.length === 1) {
    return profile.rootNodes[0]
  }
  return {
    stackTraceElement: '<multiple root nodes>',
    sampleCount: profile.rootNodes.reduce((s, n) => s + n.sampleCount, 0),
    childNodes: profile.rootNodes,
  }
}

function filterNodeMatches(node: ProfileNode, queryLower: string): boolean {
  if (!queryLower) return true
  if (
    node.stackTraceElement !== '<multiple root nodes>' &&
    (node.stackTraceElement.toLowerCase().includes(queryLower) ||
      (node.leafThreadState && node.leafThreadState.toLowerCase().includes(queryLower)))
  ) {
    return true
  }
  return Boolean(node.childNodes?.some((child) => filterNodeMatches(child, queryLower)))
}

function highlightProfileText(text: string, filterLower: string): ReactNode {
  if (!filterLower) return text
  const lower = text.toLowerCase()
  const parts: ReactNode[] = []
  let pos = 0
  let matchIdx = lower.indexOf(filterLower, pos)
  while (matchIdx !== -1) {
    if (matchIdx > pos) {
      parts.push(text.slice(pos, matchIdx))
    }
    const endIdx = matchIdx + filterLower.length
    parts.push(<strong key={matchIdx}>{text.slice(matchIdx, endIdx)}</strong>)
    pos = endIdx
    matchIdx = lower.indexOf(filterLower, pos)
  }
  if (parts.length === 0) return text
  if (pos < text.length) {
    parts.push(text.slice(pos))
  }
  return parts
}

function TraceProfileNodeView({
  node,
  unfilteredSampleCount,
  filterLower,
  depth = 0,
}: {
  node: ProfileNode
  unfilteredSampleCount: number
  filterLower: string
  depth?: number
}) {
  const [expandedChain, setExpandedChain] = useState(false)

  // Contract single-child chains just like handlebars-rendering.js:1540-1546
  const chain: ProfileNode[] = [node]
  let tail = node
  while (
    tail.childNodes &&
    tail.childNodes.length === 1 &&
    !tail.leafThreadState &&
    (tail.sampleCount === tail.childNodes[0].sampleCount ||
      tail.sampleCount < unfilteredSampleCount)
  ) {
    tail = tail.childNodes[0]
    chain.push(tail)
  }

  const matchesInChain =
    filterLower !== '' &&
    chain.some(
      (n) =>
        n.stackTraceElement !== '<multiple root nodes>' &&
        (n.stackTraceElement.toLowerCase().includes(filterLower) ||
          (n.leafThreadState && n.leafThreadState.toLowerCase().includes(filterLower)))
    )

  if (filterLower && !matchesInChain && !filterNodeMatches(tail, filterLower)) {
    return null
  }

  const displaySampleCount = tail.sampleCount
  const pct =
    unfilteredSampleCount > 0 ? formatPercent((displaySampleCount / unfilteredSampleCount) * 100) : '0'
  const sortedChildren = [...(tail.childNodes ?? [])].sort((a, b) => b.sampleCount - a.sampleCount)
  const showFullChain = expandedChain || (filterLower !== '' && chain.length > 1 && matchesInChain)

  return (
    <div>
      <div className="flex items-start py-0.5 font-mono text-xs">
        <span
          className="inline-block w-16 shrink-0 text-gray-500 tabular-nums"
          style={{ marginLeft: depth * 16 }}
        >
          {pct}%{' '}
        </span>
        {chain.length === 1 ? (
          <span className="break-all">
            {highlightProfileText(node.stackTraceElement, filterLower)}
          </span>
        ) : showFullChain ? (
          <div className="break-all">
            {chain.map((c, idx) => (
              <div
                key={idx}
                className="cursor-pointer hover:underline"
                onClick={() => setExpandedChain(false)}
              >
                {highlightProfileText(c.stackTraceElement, filterLower)}
              </div>
            ))}
          </div>
        ) : (
          <span
            className="break-all cursor-pointer"
            onClick={() => setExpandedChain(true)}
          >
            <span className="text-blue-600 font-semibold">... </span>
            <span>{highlightProfileText(tail.stackTraceElement, filterLower)}</span>
          </span>
        )}
      </div>
      {tail.leafThreadState && (
        <div
          className="py-0.5 font-mono text-xs text-gray-700"
          style={{ marginLeft: depth * 16 + 64 }}
        >
          {highlightProfileText(tail.leafThreadState, filterLower)}
        </div>
      )}
      {sortedChildren.map((child, idx) => (
        <TraceProfileNodeView
          key={idx}
          node={child}
          unfilteredSampleCount={unfilteredSampleCount}
          filterLower={filterLower}
          depth={depth + 1}
        />
      ))}
      {!tail.childNodes?.length && !tail.leafThreadState && (
        <div
          className="py-0.5 font-mono text-xs italic text-gray-500"
          style={{ marginLeft: depth * 16 + 64 }}
        >
          truncated branches
        </div>
      )}
    </div>
  )
}

function TraceProfileSection({
  agentId,
  traceId,
  checkLiveTraces,
  auxiliary,
  label,
  sampleCount,
}: {
  agentId: string
  traceId: string
  checkLiveTraces?: boolean
  auxiliary: boolean
  label: string
  sampleCount: number
}) {
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [data, setData] = useState<ProfileResponse | null>(null)
  const [filterInput, setFilterInput] = useState('')
  const [filterText, setFilterText] = useState('')

  useEffect(() => {
    const t = setTimeout(() => setFilterText(filterInput.trim().toLowerCase()), 50)
    return () => clearTimeout(t)
  }, [filterInput])

  async function toggleOpen() {
    if (loading) return
    if (data) {
      setOpen((v) => !v)
      return
    }
    setLoading(true)
    setError(false)
    try {
      const endpoint = auxiliary
        ? '/backend/trace/aux-thread-profile'
        : '/backend/trace/main-thread-profile'
      const resp = await apiGet<ProfileResponse>(endpoint, {
        'agent-id': agentId,
        'trace-id': traceId,
        ...(checkLiveTraces ? { 'check-live-traces': 'true' } : {}),
      })
      setData(resp)
      setOpen(true)
    } catch {
      setError(true)
      setOpen(true)
    } finally {
      setLoading(false)
    }
  }

  const flameParams = new URLSearchParams()
  if (agentId) flameParams.set('agent-id', agentId)
  flameParams.set('trace-id', traceId)
  if (checkLiveTraces) flameParams.set('check-live-traces', 'true')
  if (auxiliary) flameParams.set('auxiliary', 'true')
  if (filterInput.trim()) flameParams.set('filter', filterInput.trim())
  const flameGraphHref = `${getContextPath()}/modern/transaction/trace-thread-flame-graph?${flameParams.toString()}`

  const rootNode = useMemo(() => (data && data.rootNodes.length > 0 ? buildContractedRoot(data) : null), [data])

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-6 flex-wrap">
        <DetailToggle open={open} loading={loading} onClick={toggleOpen}>
          {label} ({sampleCount.toLocaleString()})
        </DetailToggle>
        <a
          href={flameGraphHref}
          target="_blank"
          rel="noreferrer"
          className="text-sm text-blue-700 hover:underline"
        >
          View flame graph
        </a>
      </div>
      {loading && <PageSpinner />}
      {error && (
        <div className="text-sm text-red-600">An error occurred retrieving the profile</div>
      )}
      {open && data && (
        <div className="ml-4 space-y-2 overflow-x-auto">
          <div>
            <input
              type="text"
              value={filterInput}
              onChange={(e) => setFilterInput(e.target.value)}
              placeholder="Filter the profile tree"
              aria-label="Profile tree filter"
              className="w-72 rounded border border-gray-300 bg-white px-2 py-1 text-xs"
            />
          </div>
          {rootNode ? (
            <TraceProfileNodeView
              node={rootNode}
              unfilteredSampleCount={data.unfilteredSampleCount}
              filterLower={filterText}
            />
          ) : (
            <div className="text-xs text-gray-500">No profile samples.</div>
          )}
        </div>
      )}
    </div>
  )
}

function buildExpiredDetailsNotice(header: TraceHeader): string | null {
  const parts: string[] = []
  if (header.entriesExistence === 'expired') parts.push('entries')
  if (header.queriesExistence === 'expired') parts.push('query stats')
  if (header.profileExistence === 'expired') parts.push('profile')
  if (parts.length === 0) return null
  let text = parts.join(', ')
  text = text.charAt(0).toUpperCase() + text.slice(1)
  const idx = text.lastIndexOf(', ')
  if (idx !== -1) {
    text = text.slice(0, idx) + ' and ' + text.slice(idx + 2)
  }
  return `${text} for this trace have expired`
}

export function TraceModal({ agentId, traceId, checkLiveTraces, onClose }: TraceModalProps) {
  const [header, setHeader] = useState<TraceHeader | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [effectiveDurationNanos, setEffectiveDurationNanos] = useState(0)
  const [locationExpanded, setLocationExpanded] = useState(false)
  const [exceptionExpanded, setExceptionExpanded] = useState(false)
  const [sharedTextCache, setSharedTextCache] = useState<
    Record<string, { fullText?: string; expired?: boolean; error?: string }>
  >({})
  const modalRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!traceId) return
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [traceId, onClose])

  useEffect(() => {
    if (!traceId) {
      setHeader(null)
      setError(false)
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(false)
    setHeader(null)
    setLocationExpanded(false)
    setExceptionExpanded(false)
    setSharedTextCache({})

    apiGet<TraceHeader>('/backend/trace/header', {
      'agent-id': agentId,
      'trace-id': traceId,
      ...(checkLiveTraces ? { 'check-live-traces': 'true' } : {}),
    })
      .then((data) => {
        if (cancelled) return
        setHeader(data)
        setEffectiveDurationNanos(data.durationNanos ?? 0)
      })
      .catch(() => {
        if (!cancelled) setError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [agentId, traceId, checkLiveTraces])

  if (!traceId) return null

  const exportParams = new URLSearchParams()
  if (agentId) exportParams.set('agent-id', agentId)
  exportParams.set('trace-id', traceId)
  if (checkLiveTraces) exportParams.set('check-live-traces', 'true')
  const exportUrl = `${getContextPath()}/export/trace?${exportParams.toString()}`
  const expiredNotice = header ? buildExpiredDetailsNotice(header) : null
  const hasAuxTimer = Boolean(header?.auxThreadRootTimer)

  return (
    <div
      id="traceModal"
      ref={modalRef}
      role="dialog"
      aria-modal="true"
      tabIndex={-1}
      className="fixed inset-0 z-50 flex flex-col bg-black/50 p-3 sm:p-6 overflow-hidden"
    >
      <div className="relative flex-1 overflow-y-auto rounded-lg bg-white shadow-2xl border border-[#d2ba7f] p-6">
        <button
          type="button"
          aria-label="Close"
          onClick={onClose}
          className="gt-close-sticky sticky top-0 float-right z-10 rounded border border-[#ccc] bg-[#edeae4] px-2.5 py-1 text-lg font-bold leading-none text-[#212529] hover:bg-[#ded8cd]"
        >
          ×
        </button>

        {loading && <PageSpinner />}

        {!loading && error && (
          <div className="gt-red text-red-600 p-4 text-sm">
            An error occurred retrieving the trace
          </div>
        )}

        {!loading && !error && header?.expired && (
          <div className="p-4 text-sm text-gray-700">expired</div>
        )}

        {!loading && !error && header && !header.expired && (
          <div className="gt-trace-parent space-y-6 pr-8">
            {/* Zone 1: Summary */}
            <section className="space-y-2">
              <div className="flex items-start gap-4 flex-wrap">
                <h2 className="text-lg font-semibold text-gray-900 break-words flex-1 min-w-[12rem]">
                  {header.headline}
                </h2>
                <button
                  type="button"
                  title="Download trace"
                  aria-label="Download trace"
                  onClick={() => {
                    window.location.href = exportUrl
                  }}
                  className="download-trace shrink-0 text-sm text-blue-700 hover:underline px-0 py-0.5 bg-transparent border-0 cursor-pointer"
                >
                  Download trace
                </button>
              </div>

              <div className="space-y-0.5 text-xs font-semibold text-gray-900">
                {header.active ? (
                  <div>ACTIVE</div>
                ) : header.partial ? (
                  <div>PARTIAL</div>
                ) : null}
                {header.error && <div>ERROR</div>}
                {header.async && <div>ASYNC</div>}
                {header.agent && <div>{header.agent}</div>}
              </div>

              <div className="space-y-0.5">
                <AttrRow label="Transaction type">{header.transactionType}</AttrRow>
                <AttrRow label="Transaction name">{header.transactionName}</AttrRow>

                {header.locationStackTraceElements &&
                  header.locationStackTraceElements.length > 0 && (
                    <div className="text-xs leading-relaxed">
                      <span className="text-gray-600">Location stack trace:&nbsp;</span>
                      <span
                        className="font-mono text-blue-700 cursor-pointer hover:underline break-all"
                        onClick={() => {
                          if (!isDragSelection()) setLocationExpanded((v) => !v)
                        }}
                      >
                        {header.locationStackTraceElements[0]}
                      </span>
                      <CopyButton text={header.locationStackTraceElements.join('\n')} />
                      {locationExpanded && (
                        <div className="mt-1 pl-4 font-mono whitespace-pre-wrap break-all text-gray-800">
                          {header.locationStackTraceElements.join('\n')}
                        </div>
                      )}
                    </div>
                  )}

                {header.error && (
                  <div className="text-xs space-y-0.5">
                    <AttrRow label="Error">
                      <span className="text-red-700">{header.error.message}</span>
                    </AttrRow>
                    {header.error.exception && (
                      <div className="pl-4">
                        <span
                          className="font-mono text-blue-700 cursor-pointer hover:underline"
                          onClick={() => {
                            if (!isDragSelection()) setExceptionExpanded((v) => !v)
                          }}
                        >
                          exception
                        </span>
                        <CopyButton text={formatThrowablePlainText(header.error.exception)} />
                        {exceptionExpanded && (
                          <div className="mt-1 pl-2">
                            <ThrowableChain throwable={header.error.exception} />
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )}

                <AttrRow label="Start">
                  {dayjs(header.startTime).format('YYYY-MM-DD h:mm:ss.SSS A (Z)')}
                </AttrRow>
                <AttrRow label="Duration">
                  {nanosToMillisStr(effectiveDurationNanos)}
                  {header.active || header.partial ? '..' : ''}
                  &nbsp;milliseconds
                </AttrRow>
                {header.user && <AttrRow label="User">{header.user}</AttrRow>}
                {header.attributes &&
                  Object.entries(header.attributes).map(([attrName, values]) =>
                    values.map((v, i) => (
                      <AttrRow key={`${attrName}-${i}`} label={attrName}>
                        {v}
                      </AttrRow>
                    ))
                  )}
                {header.detail && Object.keys(header.detail).length > 0 && (
                  <div className="pt-1">
                    <DetailTree detail={header.detail} />
                  </div>
                )}
              </div>
            </section>

            {/* Zone 2: Breakdown + JVM */}
            <section className="space-y-4 border-t border-[#e6dcc3] pt-4">
              <TraceBreakdownSection
                title={hasAuxTimer ? 'Breakdown (Main Thread):' : 'Breakdown:'}
                rootTimer={header.mainThreadRootTimer}
              />
              {header.auxThreadRootTimer && (
                <TraceBreakdownSection
                  title="Breakdown (Auxiliary Threads):"
                  rootTimer={header.auxThreadRootTimer}
                />
              )}
              {header.asyncTimers && header.asyncTimers.length > 0 && (
                <div className="space-y-1">
                  <div className="text-sm font-semibold text-gray-800">
                    Breakdown (Async Timers):
                  </div>
                  <table className="w-auto text-xs border-collapse">
                    <thead>
                      <tr className="border-b text-gray-500 text-left">
                        <th className="py-1 pr-6 font-medium">Timer</th>
                        <th className="py-1 pr-6 font-medium text-right">Total (ms)</th>
                        <th className="py-1 pr-4 font-medium text-right">Count</th>
                      </tr>
                    </thead>
                    <tbody>
                      {header.asyncTimers.map((t, i) => (
                        <tr key={i} className="border-b border-gray-100">
                          <td className="py-0.5 pr-6 text-gray-800">{t.name}</td>
                          <td className="py-0.5 pr-6 text-right tabular-nums">
                            {nanosToMillisStr(t.totalNanos)}
                            {t.active ? '..' : ''}
                          </td>
                          <td className="py-0.5 pr-4 text-right tabular-nums">
                            {t.count.toLocaleString()}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {hasAnyThreadStats(header.mainThreadStats) && (
                <ThreadStatsSection
                  title={
                    hasAuxTimer ? 'JVM Thread Stats (Main Thread):' : 'JVM Thread Stats:'
                  }
                  stats={header.mainThreadStats!}
                />
              )}
              {hasAnyThreadStats(header.auxThreadStats) && (
                <ThreadStatsSection
                  title="JVM Thread Stats (Auxiliary Threads):"
                  stats={header.auxThreadStats!}
                />
              )}
            </section>

            {/* Zone 3: Detail panels (multi-open) */}
            <section className="space-y-3 border-t border-[#e6dcc3] pt-4">
              {header.entriesExistence === 'yes' && (
                <TraceEntriesSection
                  agentId={agentId}
                  traceId={traceId}
                  checkLiveTraces={checkLiveTraces}
                  entryCount={header.entryCount}
                  baseDurationNanos={effectiveDurationNanos}
                  onDurationUpdate={setEffectiveDurationNanos}
                  sharedTextCache={sharedTextCache}
                  onCacheFullText={(sha1, val) =>
                    setSharedTextCache((prev) => ({ ...prev, [sha1]: val }))
                  }
                />
              )}
              {header.queriesExistence === 'yes' && (
                <TraceQueriesSection
                  agentId={agentId}
                  traceId={traceId}
                  checkLiveTraces={checkLiveTraces}
                  queryCount={header.queryCount}
                  sharedTextCache={sharedTextCache}
                  onCacheFullText={(sha1, val) =>
                    setSharedTextCache((prev) => ({ ...prev, [sha1]: val }))
                  }
                />
              )}
              {header.profileExistence === 'yes' && header.mainThreadProfileSampleCount > 0 && (
                <TraceProfileSection
                  agentId={agentId}
                  traceId={traceId}
                  checkLiveTraces={checkLiveTraces}
                  auxiliary={false}
                  label={
                    header.auxThreadProfileSampleCount > 0 ? 'Main Thread Profile' : 'Profile'
                  }
                  sampleCount={header.mainThreadProfileSampleCount}
                />
              )}
              {header.profileExistence === 'yes' && header.auxThreadProfileSampleCount > 0 && (
                <TraceProfileSection
                  agentId={agentId}
                  traceId={traceId}
                  checkLiveTraces={checkLiveTraces}
                  auxiliary={true}
                  label="Auxiliary Threads Profile"
                  sampleCount={header.auxThreadProfileSampleCount}
                />
              )}

              {expiredNotice && (
                <div className="text-xs font-semibold text-gray-800">{expiredNotice}</div>
              )}
            </section>
          </div>
        )}
      </div>
    </div>
  )
}
