/*
 * TransactionAveragePage — stacked area chart of average response time
 * plus timer breakdown tables (flattened + tree), async timers, and thread stats.
 *
 * Backend: GET /backend/transaction/average
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTransaction } from '../../contexts/TransactionContext'
import { useChartData } from '../../hooks/useChartData'
import { TimeSeriesChart, type PlotSeries } from '../../components/chart'
import { HelpPopover } from '../../components/shared/HelpPopover'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { formatBytes, formatMillis } from '../../lib/formatting'
import { ResponseTimeSubNav } from './ResponseTimeSubNav'

// --- Types ---

interface Timer {
  name: string
  totalNanos: number
  count: number
  extended?: boolean
  childTimers?: Timer[]
}

interface ThreadStats {
  totalCpuNanos: number
  totalBlockedNanos: number
  totalWaitedNanos: number
  totalAllocatedBytes: number
}

interface MergedAggregate {
  transactionCount: number
  mainThreadRootTimers: Timer[]
  auxThreadRootTimer?: Timer
  auxThreadRootTimers?: Timer[]
  asyncTimers?: Timer[]
  mainThreadStats?: ThreadStats
  auxThreadStats?: ThreadStats
}

interface AverageExtra {
  mergedAggregate: MergedAggregate
  transactionCounts: Record<number, number>
}

interface FlatTimer {
  name: string
  totalNanos: number
  selfNanos: number
  count: number
}

interface TreeTimer {
  name: string
  totalNanos: number
  selfNanos: number
  count: number
  extended?: boolean
  indent: number
  isOther?: boolean
}

function computeSelfNanos(timer: Timer): number {
  const childrenNanos = (timer.childTimers ?? []).reduce((s, c) => s + c.totalNanos, 0)
  return timer.totalNanos - childrenNanos
}

function createTreeTimers(rootTimers: Timer[] | undefined): TreeTimer[] {
  if (!rootTimers || rootTimers.length === 0) return []
  const result: TreeTimer[] = []
  const indent1 = 8.41 // px, sync with common-trace.less

  function traverse(timer: Timer, level: number) {
    const selfNanos = computeSelfNanos(timer)
    result.push({
      name: timer.name,
      totalNanos: timer.totalNanos,
      selfNanos,
      count: timer.count,
      extended: timer.extended,
      indent: level * indent1 * 2,
    })
    if (timer.childTimers && timer.childTimers.length > 0) {
      const sorted = [...timer.childTimers].sort((a, b) => b.totalNanos - a.totalNanos)
      for (const child of sorted) {
        traverse(child, level + 1)
      }
      if (timer.name !== '<multiple root nodes>' && selfNanos > 0) {
        result.push({
          name: 'Other',
          totalNanos: selfNanos,
          selfNanos,
          count: 0,
          indent: (level + 1) * indent1 * 2,
          isOther: true,
        })
      }
    }
  }

  let root: Timer
  if (rootTimers.length === 1) {
    root = rootTimers[0]
  } else {
    root = {
      name: '<multiple root nodes>',
      totalNanos: rootTimers.reduce((s, t) => s + t.totalNanos, 0),
      count: rootTimers.reduce((s, t) => s + t.count, 0),
      childTimers: rootTimers,
    }
  }
  traverse(root, 0)
  return result
}

function createFlattenedTimers(rootTimers: Timer[] | undefined): FlatTimer[] {
  if (!rootTimers || rootTimers.length === 0) return []
  const map: Record<string, FlatTimer> = {}
  const result: FlatTimer[] = []

  let root: Timer
  if (rootTimers.length === 1) {
    root = rootTimers[0]
  } else {
    root = {
      name: '<multiple root nodes>',
      totalNanos: rootTimers.reduce((s, t) => s + t.totalNanos, 0),
      count: rootTimers.reduce((s, t) => s + t.count, 0),
      childTimers: rootTimers,
    }
  }

  function traverse(timer: Timer, parentNames: string[]) {
    const leafNanos = computeSelfNanos(timer)
    let flat = map[timer.name]
    if (!flat) {
      flat = {
        name: timer.name,
        totalNanos: timer.totalNanos,
        selfNanos: leafNanos,
        count: timer.extended ? 0 : timer.count,
      }
      map[timer.name] = flat
      result.push(flat)
    } else {
      flat.selfNanos += leafNanos
      if (!parentNames.includes(timer.name)) {
        flat.totalNanos += timer.totalNanos
        if (!timer.extended) {
          flat.count += timer.count
        }
      }
    }
    if (timer.childTimers) {
      for (const child of timer.childTimers) {
        traverse(child, [...parentNames, timer.name])
      }
    }
  }

  traverse(root, [])
  result.sort((a, b) => b.totalNanos - a.totalNanos)
  return result
}

// --- Component ---

export function TransactionAveragePage() {
  const txn = useTransaction()

  const addToQuery = useCallback(
    (query: Record<string, string | string[] | number | undefined>) => {
      query['transaction-type'] = txn.transactionType
      if (txn.transactionName) {
        query['transaction-name'] = txn.transactionName
      }
    },
    [txn.transactionType, txn.transactionName]
  )

  const extractExtra = useCallback(
    (data: Record<string, unknown>): AverageExtra => ({
      mergedAggregate: data.mergedAggregate as MergedAggregate,
      transactionCounts: (data.transactionCounts as Record<number, number>) || {},
    }),
    []
  )

  const { chartData, loading, noData, error } = useChartData<AverageExtra>({
    url: '/backend/transaction/average',
    range: txn.range,
    refreshCounter: txn.refreshCounter,
    addToQuery,
    extractExtra,
  })

  const mergedAggregate = chartData?.extra.mergedAggregate
  const auxRootTimers = useMemo(() => {
    if (!mergedAggregate) return undefined
    if (mergedAggregate.auxThreadRootTimers?.length) {
      return mergedAggregate.auxThreadRootTimers
    }
    if (mergedAggregate.auxThreadRootTimer) {
      return [mergedAggregate.auxThreadRootTimer]
    }
    return undefined
  }, [mergedAggregate])

  const mainTreeTimers = useMemo(
    () => createTreeTimers(mergedAggregate?.mainThreadRootTimers),
    [mergedAggregate]
  )
  const auxTreeTimers = useMemo(
    () => createTreeTimers(auxRootTimers),
    [auxRootTimers]
  )
  const mainFlatTimers = useMemo(
    () => createFlattenedTimers(mergedAggregate?.mainThreadRootTimers),
    [mergedAggregate]
  )
  const auxFlatTimers = useMemo(
    () => createFlattenedTimers(auxRootTimers),
    [auxRootTimers]
  )
  const sortedAsyncTimers = useMemo(() => {
    if (!mergedAggregate?.asyncTimers?.length) return []
    return [...mergedAggregate.asyncTimers].sort((a, b) => b.totalNanos - a.totalNanos)
  }, [mergedAggregate])

  const [timerView, setTimerView] = useState<'flat' | 'tree'>('flat')

  // Tooltip
  const transactionCounts = chartData?.extra.transactionCounts
  const tooltipContent = useCallback(
    (
      from: number,
      to: number | null,
      dataIndex: number,
      seriesIndex: number,
      allSeries: PlotSeries[]
    ) => {
      const total = allSeries.reduce((sum, s) => {
        const val = s.data[dataIndex]?.[1]
        return sum + (val ?? 0)
      }, 0)
      if (total === 0) return 'No data'

      function timeStr(ms: number): string {
        return new Date(ms).toLocaleTimeString(undefined, {
          hour: 'numeric',
          minute: '2-digit',
        })
      }

      const xval = allSeries[0]?.data[dataIndex]?.[0]
      const count = xval !== undefined && transactionCounts ? transactionCounts[xval] : undefined

      let html = `<div style="font-weight:600;margin-bottom:2px">${timeStr(from)}`
      if (to) html += ` to ${timeStr(to)}`
      html += '</div>'
      if (count !== undefined) {
        html += `<div style="font-size:11px;color:#555;margin-bottom:2px">Transaction count: ${count.toLocaleString()}</div>`
      }
      html += '<table style="border-spacing:4px 1px">'

      for (let si = 0; si < allSeries.length; si++) {
        const s = allSeries[si]
        const val = s.data[dataIndex]?.[1] ?? 0
        const pct = total > 0 ? ((100 * val) / total).toFixed(1) + ' %' : '0 %'
        html += `<tr${si === seriesIndex ? ' style="background:#eee"' : ''}>`
        html += `<td><span style="display:inline-block;width:10px;height:10px;background:${s.color};border:1px solid #ccc"></span></td>`
        html += `<td>${s.name || 'Other'}</td>`
        html += `<td style="font-weight:600;text-align:right">${formatMillis(val)} ms</td>`
        html += `<td style="color:#666;text-align:right">(${pct})</td>`
        html += '</tr>'
      }
      html += '</table>'
      return html
    },
    [transactionCounts]
  )

  const qs = txn.buildTabQueryString()
  const basePath = `/modern/${txn.shortName}`

  if (loading && !chartData) return <PageSpinner />
  if (error && !chartData) return <HttpError error={error} />

  return (
    <div className="space-y-4">
      <ResponseTimeSubNav
        active="average"
        basePath={basePath}
        qs={qs}
        onRefreshActive={txn.rangeActions.refresh}
        onZoomOut={txn.rangeActions.zoomOut}
        help={
          <div className="space-y-2 text-xs">
            <div>
              <div className="font-semibold">Chart navigation tips</div>
              <ul className="list-disc pl-4 space-y-0.5 mt-1">
                <li>Drag or double click to zoom in</li>
                <li>Use Ctrl + scroll wheel to zoom in and out</li>
              </ul>
            </div>
            <div>
              <div className="font-semibold">Chart vs breakdown</div>
              <ul className="list-disc pl-4 space-y-0.5 mt-1">
                <li>Chart stacks <em>self time</em> (exclusive); stack height is average response time</li>
                <li>Breakdown <em>total</em> is inclusive; use <em>self</em> to compare with chart segments</li>
              </ul>
            </div>
          </div>
        }
      />

      {/* Chart */}
      <div className="rounded-lg border bg-white p-4">
        {chartData && chartData.dataSeries.length > 0 ? (
          <TimeSeriesChart
            dataSeries={chartData.dataSeries}
            chartFrom={txn.range.chartFrom}
            chartTo={txn.range.chartTo}
            dataPointIntervalMillis={chartData.dataPointIntervalMillis}
            markings={chartData.markings}
            stacked={true}
            yAxisLabel="milliseconds"
            tooltipContent={tooltipContent}
            onZoom={txn.rangeActions.handleZoom}
            onSelection={txn.rangeActions.handleSelection}
          />
        ) : (
          <div className="flex items-center justify-center h-[300px] text-sm text-gray-500">
            {noData ? 'No data for selected time range' : 'Loading...'}
          </div>
        )}
      </div>

      {/* Timer breakdown */}
      {mergedAggregate && mergedAggregate.transactionCount > 0 && (
        <div className="rounded-lg border bg-white p-4 space-y-4">
          <div className="text-xs text-gray-500">
            The chart shows stacked self time (exclusive). The breakdown table&apos;s total column is inclusive (parents include nested timers); compare the self column to the chart segments. Inclusive totals do not need to sum to the chart total.
          </div>
          {/* View toggle */}
          <div className="flex gap-4 text-sm">
            <button
              className={timerView === 'flat' ? 'font-medium text-blue-600' : 'text-gray-500 hover:text-gray-700'}
              onClick={() => setTimerView('flat')}
            >
              Flattened
            </button>
            <button
              className={timerView === 'tree' ? 'font-medium text-blue-600' : 'text-gray-500 hover:text-gray-700'}
              onClick={() => setTimerView('tree')}
            >
              Tree
            </button>
          </div>

          {/* Main thread timers */}
          <TimerBreakdownSection
            title="Main thread timers"
            timerView={timerView}
            flatTimers={mainFlatTimers}
            treeTimers={mainTreeTimers}
            transactionCount={mergedAggregate.transactionCount}
          />

          {/* Aux thread timers */}
          {auxTreeTimers.length > 0 && (
            <TimerBreakdownSection
              title="Auxiliary thread timers"
              timerView={timerView}
              flatTimers={auxFlatTimers}
              treeTimers={auxTreeTimers}
              transactionCount={mergedAggregate.transactionCount}
            />
          )}

          {/* Async timers */}
          {sortedAsyncTimers.length > 0 && (
            <AsyncTimersTable
              timers={sortedAsyncTimers}
              transactionCount={mergedAggregate.transactionCount}
            />
          )}

          {/* Thread stats */}
          {mergedAggregate.mainThreadStats && hasThreadStats(mergedAggregate.mainThreadStats) && (
            <ThreadStatsTable
              title="Main thread stats"
              stats={mergedAggregate.mainThreadStats}
              transactionCount={mergedAggregate.transactionCount}
            />
          )}
          {mergedAggregate.auxThreadStats && hasThreadStats(mergedAggregate.auxThreadStats) && (
            <ThreadStatsTable
              title="Auxiliary thread stats"
              stats={mergedAggregate.auxThreadStats}
              transactionCount={mergedAggregate.transactionCount}
            />
          )}
        </div>
      )}
    </div>
  )
}

function hasThreadStats(stats: ThreadStats): boolean {
  return (
    stats.totalCpuNanos !== -1 ||
    stats.totalBlockedNanos !== -1 ||
    stats.totalWaitedNanos !== -1 ||
    stats.totalAllocatedBytes !== -1
  )
}

function TimerBreakdownSection({
  title,
  timerView,
  flatTimers,
  treeTimers,
  transactionCount,
}: {
  title: string
  timerView: 'flat' | 'tree'
  flatTimers: FlatTimer[]
  treeTimers: TreeTimer[]
  transactionCount: number
}) {
  const [limit, setLimit] = useState(10)

  useEffect(() => {
    setLimit(10)
  }, [flatTimers, treeTimers])

  const activeTimers = timerView === 'flat' ? flatTimers : treeTimers
  if (activeTimers.length === 0) return null

  const sortedTotals = [...activeTimers]
    .map((t) => t.totalNanos)
    .sort((a, b) => b - a)
  const effectiveLimit = Math.min(limit, sortedTotals.length)
  const thresholdNanos = effectiveLimit > 0 ? sortedTotals[effectiveLimit - 1] : 0

  let shownCount = 0
  const visibleFlat =
    timerView === 'flat'
      ? flatTimers.filter((t) => {
          if (t.totalNanos >= thresholdNanos && shownCount < limit) {
            shownCount++
            return true
          }
          return false
        })
      : []

  shownCount = 0
  const visibleTree =
    timerView === 'tree'
      ? treeTimers.filter((t) => {
          if (t.totalNanos >= thresholdNanos && shownCount < limit) {
            shownCount++
            return true
          }
          return false
        })
      : []

  const hasMore = limit < activeTimers.length
  const showLess = limit > 10

  return (
    <div>
      <h3 className="text-sm font-medium text-gray-700 mb-2">{title}</h3>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-gray-500">
            <th className="py-1 font-normal">Breakdown</th>
            <th className="py-1 font-normal text-right">Total (ms)</th>
            <th className="py-1 font-normal text-right">Self (ms)</th>
            <th className="py-1 font-normal text-right">Count</th>
          </tr>
        </thead>
        <tbody>
          {timerView === 'flat'
            ? visibleFlat.map((timer, i) => (
                <tr key={i} className="border-b last:border-b-0">
                  <td className="py-1">{timer.name}</td>
                  <td className="py-1 text-right tabular-nums">
                    {formatMillis(timer.totalNanos / (1000000 * transactionCount))}
                  </td>
                  <td className="py-1 text-right tabular-nums">
                    {formatMillis(timer.selfNanos / (1000000 * transactionCount))}
                  </td>
                  <td className="py-1 text-right tabular-nums">
                    {timer.count > 0 ? (timer.count / transactionCount).toFixed(1) : ''}
                  </td>
                </tr>
              ))
            : visibleTree.map((timer, i) => (
                <tr key={i} className="border-b last:border-b-0">
                  <td
                    className={`py-1${timer.isOther ? ' italic text-gray-500' : ''}`}
                    style={{ paddingLeft: timer.indent }}
                  >
                    {timer.name}
                    {timer.extended ? ' (extended)' : ''}
                  </td>
                  <td className="py-1 text-right tabular-nums">
                    {formatMillis(timer.totalNanos / (1000000 * transactionCount))}
                  </td>
                  <td className="py-1 text-right tabular-nums">
                    {formatMillis(timer.selfNanos / (1000000 * transactionCount))}
                  </td>
                  <td className="py-1 text-right tabular-nums">
                    {timer.extended || timer.isOther || timer.count === 0
                      ? ''
                      : (timer.count / transactionCount).toFixed(1)}
                  </td>
                </tr>
              ))}
        </tbody>
      </table>
      {(hasMore || showLess) && (
        <div className="mt-2 flex gap-3 text-xs">
          {hasMore && (
            <button
              className="text-blue-600 hover:underline"
              onClick={() => setLimit((prev) => prev * 2)}
            >
              Show more
            </button>
          )}
          {showLess && (
            <button
              className="text-blue-600 hover:underline"
              onClick={() => {
                setLimit((prev) => {
                  let next = prev / 2
                  while (next >= activeTimers.length && next > 10) {
                    next /= 2
                  }
                  return Math.max(10, next)
                })
              }}
            >
              Show less
            </button>
          )}
          {timerView === 'tree' && hasMore && (
            <button
              className="text-blue-600 hover:underline"
              onClick={() => {
                let next = limit
                while (next < activeTimers.length) {
                  next *= 2
                }
                setLimit(next)
              }}
            >
              Show all
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function AsyncTimersTable({
  timers,
  transactionCount,
}: {
  timers: Timer[]
  transactionCount: number
}) {
  return (
    <div>
      <h3 className="text-sm font-medium text-gray-700 mb-2">Async timers</h3>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-gray-500">
            <th className="py-1 font-normal">Breakdown</th>
            <th className="py-1 font-normal text-right">Total (ms)</th>
            <th className="py-1 font-normal text-right">Count</th>
          </tr>
        </thead>
        <tbody>
          {timers.map((timer, i) => (
            <tr key={i} className="border-b last:border-b-0">
              <td className="py-1">{timer.name}</td>
              <td className="py-1 text-right tabular-nums">
                {formatMillis(timer.totalNanos / (1000000 * transactionCount))}
              </td>
              <td className="py-1 text-right tabular-nums">
                {timer.extended || timer.count === 0
                  ? ''
                  : (timer.count / transactionCount).toFixed(1)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ThreadStatsTable({
  title,
  stats,
  transactionCount,
}: {
  title: string
  stats: ThreadStats
  transactionCount: number
}) {
  const rows: Array<{ label: string; value: string }> = []
  if (stats.totalCpuNanos !== -1) {
    rows.push({
      label: 'CPU time',
      value: formatMillis(stats.totalCpuNanos / (1000000 * transactionCount)) + ' ms',
    })
  }
  if (stats.totalBlockedNanos !== -1) {
    rows.push({
      label: 'Blocked time',
      value: formatMillis(stats.totalBlockedNanos / (1000000 * transactionCount)) + ' ms',
    })
  }
  if (stats.totalWaitedNanos !== -1) {
    rows.push({
      label: 'Waited time',
      value: formatMillis(stats.totalWaitedNanos / (1000000 * transactionCount)) + ' ms',
    })
  }
  if (stats.totalAllocatedBytes !== -1) {
    rows.push({
      label: 'Allocated memory',
      value: formatBytes(stats.totalAllocatedBytes / transactionCount),
    })
  }
  if (rows.length === 0) return null
  return (
    <div>
      <h3 className="text-sm font-medium text-gray-700 mb-2">{title}</h3>
      <table className="text-sm">
        <tbody>
          {rows.map((row) => (
            <tr key={row.label}>
              <td className="py-0.5 pr-4 text-gray-500">
                <span className="inline-flex items-center gap-1">
                  <span>{row.label}</span>
                  {row.label === 'Allocated memory' && (
                    <HelpPopover title="Allocated memory">
                      Total memory allocated by the thread during the transaction, including memory that was subsequently garbage collected.
                    </HelpPopover>
                  )}
                </span>
              </td>
              <td className="py-0.5 tabular-nums">{row.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
