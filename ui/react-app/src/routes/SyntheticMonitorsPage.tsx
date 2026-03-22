/*
 * Synthetic monitors page — response time chart, error marking tooltips, and monitor selector.
 *
 * Replaces synthetic-monitors.js & synthetic-monitors.html.
 */

import { useCallback, useEffect, useMemo } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ZoomOut, RefreshCw } from 'lucide-react'
import { useLayout } from '../contexts/LayoutContext'
import { useAgent } from '../contexts/AgentContext'
import { AgentSelector } from '../components/layout/AgentSelector'
import { TimeSeriesChart, type PlotSeries } from '../components/chart'
import { ChartRangeSelector } from '../components/chart/ChartRangeSelector'
import { useChartRange } from '../hooks/useChartRange'
import { useChartData, type DeploymentMarking } from '../hooks/useChartData'
import { HelpPopover } from '../components/shared/HelpPopover'
import { PageSpinner } from '../components/shared/Spinner'
import { HttpError } from '../components/shared/HttpError'
import { formatMillis } from '../lib/formatting'

interface SyntheticMonitorItem {
  id: string
  display: string
}

interface SyntheticExtra {
  executionCounts: Record<number, Record<number, number>>
  allSyntheticMonitors: SyntheticMonitorItem[]
  displayNoSyntheticMonitorsConfigured: boolean
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function smartFormatTime(millis: number): string {
  const d = new Date(millis)
  if (millis % 60000 === 0) {
    return d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
  }
  return d.toLocaleTimeString()
}

export function SyntheticMonitorsPage() {
  const { layout } = useLayout()
  const { agentRollupId } = useAgent()
  const [range, rangeActions, refreshCounter] = useChartRange(4 * 60 * 60 * 1000)

  useEffect(() => {
    document.title = 'Synthetic \u00b7 Glowroot'
  }, [])

  const hideMainContent = layout.central && !agentRollupId

  return (
    <div>
      <div className="gt-header flex flex-wrap items-center justify-between gap-4 pt-5 pb-12">
        <div className="gt-header-lhs flex flex-wrap items-center gap-1">
          {layout.central && (
            <>
              <AgentSelector />
              <div
                aria-hidden="true"
                className="gt-header-text gt-separator mx-1.5 -mt-2 select-none text-[3.25rem] font-normal leading-none text-[#785b12]"
              >
                |
              </div>
            </>
          )}
          <h1 className="gt-header-text m-0 text-[2.25rem] font-bold leading-none text-[#785b12]">
            Synthetic monitors
          </h1>
        </div>
        <div className="gt-header-rhs flex items-center pt-2 print:hidden">
          <ChartRangeSelector range={range} actions={rangeActions} />
        </div>
      </div>

      <div className="rounded border border-[#d2ba7f] bg-white">
        <div className="border-b border-[#d2ba7f] bg-[#edeae4] px-5 py-3 text-[#555]">
          <h2 className="m-0 text-[1.75rem] font-semibold leading-tight text-[#555]">
            Synthetic monitors
          </h2>
        </div>
        <div className="p-6">
          {!hideMainContent ? (
            <SyntheticMonitorsContent
              range={range}
              rangeActions={rangeActions}
              refreshCounter={refreshCounter}
            />
          ) : (
            <div className="min-h-[40px]" />
          )}
        </div>
      </div>
    </div>
  )
}

function SyntheticMonitorsContent({
  range,
  rangeActions,
  refreshCounter,
}: {
  range: ReturnType<typeof useChartRange>[0]
  rangeActions: ReturnType<typeof useChartRange>[1]
  refreshCounter: number
}) {
  const { layout } = useLayout()
  const { agentQueryString } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()

  const hasMonitorParam = searchParams.has('synthetic-monitor-id')
  const showingAll = !hasMonitorParam
  const explicitMonitorIds = useMemo(() => {
    if (showingAll) return [] as string[]
    return searchParams.getAll('synthetic-monitor-id').filter((id) => id !== '')
  }, [showingAll, searchParams])

  const addToQuery = useCallback(
    (query: Record<string, string | string[] | number | undefined>) => {
      if (showingAll) {
        query.all = 'true'
      } else if (explicitMonitorIds.length > 0) {
        query['synthetic-monitor-id'] = explicitMonitorIds
      }
    },
    [showingAll, explicitMonitorIds]
  )

  const extractExtra = useCallback(
    (data: Record<string, unknown>): SyntheticExtra => ({
      executionCounts:
        (data.executionCounts as Record<number, Record<number, number>>) || {},
      allSyntheticMonitors:
        (data.allSyntheticMonitors as SyntheticMonitorItem[]) || [],
      displayNoSyntheticMonitorsConfigured: Boolean(
        data.displayNoSyntheticMonitorsConfigured
      ),
    }),
    []
  )

  const { chartData, loading, noData, error } = useChartData<SyntheticExtra>({
    url: '/backend/synthetic-monitor/results',
    range,
    refreshCounter,
    addToQuery,
    extractExtra,
  })

  const allSyntheticMonitors = chartData?.extra.allSyntheticMonitors ?? []
  const displayNoConfigured = chartData?.extra.displayNoSyntheticMonitorsConfigured ?? false
  const executionCounts = chartData?.extra.executionCounts ?? {}

  const displayMap = useMemo(() => {
    const map: Record<string, string> = {}
    for (const m of allSyntheticMonitors) {
      map[m.id] = m.display
    }
    return map
  }, [allSyntheticMonitors])

  const selectedIds = useMemo(
    () => (showingAll ? allSyntheticMonitors.map((m) => m.id) : explicitMonitorIds),
    [showingAll, allSyntheticMonitors, explicitMonitorIds]
  )

  const selectedSet = useMemo(() => new Set(selectedIds), [selectedIds])

  const { seriesWithLabels, yvalMaps } = useMemo(() => {
    const series = chartData?.dataSeries ?? []
    const maps: Record<string, Record<number, number>> = {}
    for (const s of series) {
      const m: Record<number, number> = {}
      for (const pt of s.data) {
        if (pt != null && pt[1] != null) {
          m[pt[0]] = pt[1]
        }
      }
      maps[s.name] = m
    }
    return {
      seriesWithLabels: series.map((s) => ({
        ...s,
        shortLabel: displayMap[s.name] || s.name,
      })),
      yvalMaps: maps,
    }
  }, [chartData?.dataSeries, displayMap])

  function toggleMonitor(monitorId: string) {
    const nextSet = new Set(selectedIds)
    if (nextSet.has(monitorId)) {
      nextSet.delete(monitorId)
    } else {
      nextSet.add(monitorId)
    }
    // Preserve server ordering (case-insensitive display order)
    const next = allSyntheticMonitors
      .map((m) => m.id)
      .filter((id) => nextSet.has(id))

    setSearchParams((prev) => {
      const updated = new URLSearchParams(prev)
      updated.delete('synthetic-monitor-id')
      if (allSyntheticMonitors.length > 0 && next.length === allSyntheticMonitors.length) {
        // Default "all": no synthetic-monitor-id param
        return updated
      }
      if (next.length === 0) {
        // Differentiate between no synthetic monitors selected and default "all"
        updated.set('synthetic-monitor-id', '')
        return updated
      }
      for (const id of next) {
        updated.append('synthetic-monitor-id', id)
      }
      return updated
    })
  }

  function updateAllMonitorsSelection(selectAll: boolean) {
    setSearchParams((prev) => {
      const updated = new URLSearchParams(prev)
      updated.delete('synthetic-monitor-id')
      if (!selectAll) {
        updated.set('synthetic-monitor-id', '')
      }
      return updated
    })
  }

  const tooltipContent = useCallback(
    (
      from: number,
      to: number | null,
      _dataIndex: number,
      seriesIndex: number,
      allSeries: PlotSeries[]
    ) => {
      const interval = chartData?.dataPointIntervalMillis ?? 0
      const level0Interval = layout.rollupConfigs[0]?.intervalMillis
      const targetTs = to ?? from

      if (level0Interval && interval === level0Interval) {
        const s = allSeries[seriesIndex]
        if (!s) return ''
        const yval = yvalMaps[s.name]?.[targetTs]
        const title = escapeHtml(displayMap[s.name] || s.name)
        const d = new Date(targetTs)
        const timeStr = `${d.toLocaleTimeString()}.${String(d.getMilliseconds()).padStart(3, '0')}`
        const valStr = yval !== undefined ? `${formatMillis(yval)} milliseconds` : 'no data'
        return (
          '<table class="gt-chart-tooltip">' +
          `<tr><td colspan="2" style="font-weight:600">${title}</td></tr>` +
          `<tr><td style="padding-right:10px">Time:</td><td style="font-weight:400">${escapeHtml(timeStr)}</td></tr>` +
          `<tr><td style="padding-right:10px">Value:</td><td style="font-weight:600">${escapeHtml(valStr)}</td></tr>` +
          '</table>'
        )
      }

      const bucketFrom =
        interval > 0 ? Math.ceil((targetTs - interval) / interval) * interval : from
      let html =
        `<div style="font-weight:600;margin-bottom:2px">${escapeHtml(smartFormatTime(bucketFrom))} to ${escapeHtml(smartFormatTime(targetTs))}` +
        '<span style="font-weight:400"> (average value over this interval)</span></div>'
      html += '<table style="border-spacing:4px 1px">'
      for (let si = 0; si < allSeries.length; si++) {
        const s = allSeries[si]
        const rawVal = yvalMaps[s.name]?.[targetTs]
        const displayLabel = escapeHtml(displayMap[s.name] || s.name)
        let text: string
        if (rawVal === undefined) {
          text = 'no data'
        } else {
          const count = executionCounts[si]?.[targetTs] ?? 0
          text = `${formatMillis(rawVal)} milliseconds over ${count} executions`
        }
        html += `<tr${si === seriesIndex ? ' style="background:#eee"' : ''}>`
        html += `<td><span style="display:inline-block;width:10px;height:10px;background:${s.color};border:1px solid #ccc"></span></td>`
        html += `<td>${displayLabel}</td>`
        html += `<td style="font-weight:600">${escapeHtml(text)}</td>`
        html += '</tr>'
      }
      html += '</table>'
      return html
    },
    [chartData?.dataPointIntervalMillis, layout.rollupConfigs, yvalMaps, displayMap, executionCounts]
  )

  const markingTooltipContent = useCallback(
    (marking: DeploymentMarking) => {
      let html =
        '<table class="gt-chart-tooltip"><thead><tr><td colspan="2" style="font-weight:600">' +
        `${escapeHtml(smartFormatTime(marking.from))} to ${escapeHtml(smartFormatTime(marking.to))}` +
        '</td></tr></thead><tbody>'
      const entries = Object.entries(marking.intervals ?? {})
      for (const [monitorId, intervals] of entries) {
        if (selectedIds.length > 1) {
          html += `<tr><td colspan="2">${escapeHtml(displayMap[monitorId] || monitorId)}</td></tr>`
        }
        if (selectedIds.length === 1 && intervals.length === 1) {
          const iv = intervals[0]
          html += `<tr><td colspan="2">${escapeHtml(iv.message)} (${iv.count} results)</td></tr>`
        } else {
          for (const iv of intervals) {
            html +=
              `<tr><td style="padding-right:10px">${escapeHtml(smartFormatTime(iv.from))} to ${escapeHtml(smartFormatTime(iv.to))}</td>` +
              `<td><span style="font-weight:600">${escapeHtml(iv.message)}</span> (${iv.count} results)</td></tr>`
          }
        }
      }
      html += '</tbody></table>'
      return html
    },
    [selectedIds.length, displayMap]
  )

  if (loading && !chartData) return <PageSpinner />
  if (error != null && !chartData) return <HttpError error={error} />

  const showChartData = chartData != null && seriesWithLabels.length > 0 && !noData

  return (
    <div className="space-y-4">
      {error != null && <HttpError error={error} />}

      {displayNoConfigured ? (
        <div className="rounded-lg border bg-white p-6 text-base text-gray-700">
          No synthetic monitors configured. See{' '}
          <Link
            to={`/modern/config/synthetic-monitor-list${agentQueryString()}`}
            className="text-blue-600 hover:underline"
          >
            Synthetic monitor configuration
          </Link>
          .
        </div>
      ) : (
        <>
          <div className="rounded-lg border bg-white p-4 relative">
            <div className="flex justify-end gap-1 mb-2">
              <button
                type="button"
                onClick={rangeActions.zoomOut}
                title="Zoom out"
                className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
              >
                <ZoomOut className="h-4 w-4" />
              </button>
              <button
                type="button"
                onClick={rangeActions.refresh}
                title="Refresh"
                className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
              >
                <RefreshCw className="h-4 w-4" />
              </button>
              <HelpPopover title="Chart navigation" align="right">
                <div className="space-y-1 text-xs">
                  <div className="font-semibold">Chart navigation tips</div>
                  <ul className="list-disc pl-4 space-y-0.5">
                    <li>Drag or double click to zoom in</li>
                    <li>Use Ctrl + scroll wheel to zoom in and out</li>
                  </ul>
                </div>
              </HelpPopover>
            </div>
            {showChartData ? (
              <TimeSeriesChart
                dataSeries={seriesWithLabels}
                chartFrom={range.chartFrom}
                chartTo={range.chartTo}
                dataPointIntervalMillis={chartData.dataPointIntervalMillis}
                markings={chartData.markings}
                stacked={false}
                yAxisLabel="milliseconds"
                tooltipContent={tooltipContent}
                markingTooltipContent={markingTooltipContent}
                onZoom={rangeActions.handleZoom}
                onSelection={rangeActions.handleSelection}
              />
            ) : (
              <div className="flex items-center justify-center h-[300px] text-sm text-gray-500">
                {loading
                  ? 'Loading...'
                  : allSyntheticMonitors.length === 0
                    ? 'No synthetic monitors captured in this time period'
                    : selectedIds.length === 0
                      ? 'Select one or more synthetic monitors below'
                      : 'No data'}
              </div>
            )}
          </div>

          {allSyntheticMonitors.length > 0 && (
            <div className="rounded-lg border bg-white p-4 space-y-2">
              <div className="flex items-center gap-3 pb-1 border-b border-gray-100 text-xs">
                <button
                  type="button"
                  onClick={() => updateAllMonitorsSelection(true)}
                  className="text-blue-600 hover:underline font-medium"
                >
                  Select all
                </button>
                <button
                  type="button"
                  onClick={() => updateAllMonitorsSelection(false)}
                  className="text-blue-600 hover:underline font-medium"
                >
                  Deselect all
                </button>
              </div>
              {allSyntheticMonitors.map((monitor) => {
                const checked = selectedSet.has(monitor.id)
                return (
                  <label
                    key={monitor.id}
                    className={`flex items-center gap-2 cursor-pointer text-sm ${
                      checked ? 'font-semibold text-gray-900' : 'text-gray-700'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleMonitor(monitor.id)}
                      className="h-4 w-4 rounded border-gray-300 text-blue-600"
                    />
                    <span>{monitor.display}</span>
                  </label>
                )
              })}
            </div>
          )}
        </>
      )}
    </div>
  )
}
