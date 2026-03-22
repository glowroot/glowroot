/*
 * TransactionPercentilesPage — multi-line chart of response time percentiles.
 *
 * Backend: GET /backend/transaction/percentiles
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { RefreshCw, ZoomOut } from 'lucide-react'
import { useTransaction } from '../../contexts/TransactionContext'
import { useAgent } from '../../contexts/AgentContext'
import { useChartData } from '../../hooks/useChartData'
import { TimeSeriesChart, type PlotSeries } from '../../components/chart'
import { HelpPopover } from '../../components/shared/HelpPopover'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { formatMillis } from '../../lib/formatting'

interface PercentileValue {
  dataSeriesName: string
  value: number
}

interface PercentileMergedAggregate {
  transactionCount?: number
  totalDurationNanos?: number
  percentileValues?: PercentileValue[]
}

interface PercentilesExtra {
  mergedAggregate: PercentileMergedAggregate
  transactionCounts: Record<number, number>
}

function parsePercentileList(values: string[]): number[] {
  const valid = new Set<number>()
  for (const raw of values) {
    const trimmed = raw.trim()
    if (!trimmed) continue
    const num = Number(trimmed)
    if (Number.isFinite(num) && num > 0 && num < 100) {
      valid.add(num)
    }
  }
  return Array.from(valid).sort((a, b) => a - b)
}

function arraysEqual(a: number[], b: number[]): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i])
}

export function TransactionPercentilesPage() {
  const txn = useTransaction()
  const { agentRollup } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()
  const defaultPercentiles = useMemo(
    () => agentRollup?.defaultPercentiles ?? [50, 95, 99],
    [agentRollup?.defaultPercentiles]
  )

  const urlPercentilesKey = searchParams.getAll('percentile').join(',')
  const percentiles = useMemo(() => {
    const fromUrl = parsePercentileList(searchParams.getAll('percentile'))
    return fromUrl.length > 0 ? fromUrl : defaultPercentiles
  }, [urlPercentilesKey, searchParams, defaultPercentiles])

  const [customOpen, setCustomOpen] = useState(false)
  const [customInput, setCustomInput] = useState('')
  const [customError, setCustomError] = useState('')

  useEffect(() => {
    setCustomError('')
  }, [customOpen])

  const addToQuery = useCallback(
    (query: Record<string, string | string[] | number | undefined>) => {
      query['transaction-type'] = txn.transactionType
      if (txn.transactionName) {
        query['transaction-name'] = txn.transactionName
      }
      query['percentile'] = percentiles.map(String)
    },
    [txn.transactionType, txn.transactionName, percentiles]
  )

  const extractExtra = useCallback(
    (data: Record<string, unknown>): PercentilesExtra => ({
      mergedAggregate: (data.mergedAggregate as PercentileMergedAggregate) || {},
      transactionCounts: (data.transactionCounts as Record<number, number>) || {},
    }),
    []
  )

  const { chartData, loading, noData, error } = useChartData<PercentilesExtra>({
    url: '/backend/transaction/percentiles',
    range: txn.range,
    refreshCounter: txn.refreshCounter,
    addToQuery,
    extractExtra,
  })

  const transactionCounts = chartData?.extra.transactionCounts
  const tooltipContent = useCallback(
    (
      from: number,
      to: number | null,
      dataIndex: number,
      seriesIndex: number,
      allSeries: PlotSeries[]
    ) => {
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
        const val = s.data[dataIndex]?.[1]
        const display = val != null ? formatMillis(val) + ' ms' : 'no data'
        html += `<tr${si === seriesIndex ? ' style="background:#eee"' : ''}>`
        html += `<td><span style="display:inline-block;width:10px;height:10px;background:${s.color};border:1px solid #ccc"></span></td>`
        html += `<td>${s.name || 'Other'}</td>`
        html += `<td style="font-weight:600">${display}</td>`
        html += '</tr>'
      }
      html += '</table>'
      return html
    },
    [transactionCounts]
  )

  function applyCustomPercentiles() {
    const parts = customInput
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0)
    if (parts.length === 0) {
      setCustomError('Enter at least one percentile strictly between 0 and 100.')
      return
    }
    const parsedNums: number[] = []
    for (const part of parts) {
      const n = Number(part)
      if (!Number.isFinite(n) || n <= 0 || n >= 100) {
        setCustomError('Percentiles must be strictly between 0 and 100.')
        return
      }
      parsedNums.push(n)
    }
    const nextPercentiles = Array.from(new Set(parsedNums)).sort((a, b) => a - b)
    setCustomError('')
    setCustomOpen(false)
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.delete('percentile')
      if (!arraysEqual(nextPercentiles, defaultPercentiles)) {
        for (const p of nextPercentiles) {
          next.append('percentile', String(p))
        }
      }
      return next
    })
  }

  const qs = txn.buildTabQueryString()
  const basePath = `/modern/${txn.shortName}`
  const merged = chartData?.extra.mergedAggregate

  if (loading && !chartData) return <PageSpinner />
  if (error && !chartData) return <HttpError error={error} />

  return (
    <div className="space-y-4">
      {/* Response time sub-nav */}
      <div className="flex items-center justify-between gap-4 text-sm flex-wrap">
        <div className="flex gap-4 items-center">
          <Link to={`${basePath}/average${qs}`} className="text-gray-500 hover:text-gray-700">
            Average
          </Link>
          <Link
            to={`${basePath}/percentiles${qs}`}
            onClick={(e) => {
              e.preventDefault()
              txn.rangeActions.refresh()
            }}
            className="font-medium text-blue-600 no-underline"
          >
            Percentiles
          </Link>
          <Link to={`${basePath}/throughput${qs}`} className="text-gray-500 hover:text-gray-700">
            Throughput
          </Link>
          <span className="text-gray-300">|</span>
          <span className="text-xs text-gray-500">{percentiles.join(', ')}</span>
          <button
            className="text-xs text-blue-600 hover:underline"
            onClick={() => {
              setCustomInput(percentiles.join(', '))
              setCustomOpen(true)
            }}
          >
            customize
          </button>
        </div>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={txn.rangeActions.zoomOut}
            title="Zoom out"
            aria-label="Zoom out"
            className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
          >
            <ZoomOut className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={txn.rangeActions.refresh}
            title="Refresh"
            aria-label="Refresh"
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
      </div>

      {/* Custom percentiles dialog */}
      {customOpen && (
        <div className="rounded-lg border bg-white p-4 space-y-2">
          <div className="flex items-center gap-3 flex-wrap">
            <label className="text-sm text-gray-600">Percentiles (comma-separated, 0 &lt; p &lt; 100):</label>
            <input
              className="rounded border px-2 py-1 text-sm w-48"
              value={customInput}
              onChange={(e) => {
                setCustomInput(e.target.value)
                setCustomError('')
              }}
              onKeyDown={(e) => e.key === 'Enter' && applyCustomPercentiles()}
              autoFocus
            />
            <button
              className="rounded bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-700"
              onClick={applyCustomPercentiles}
            >
              Apply
            </button>
            <button
              className="text-sm text-gray-500 hover:text-gray-700"
              onClick={() => setCustomOpen(false)}
            >
              Cancel
            </button>
          </div>
          {customError && <div className="text-xs text-red-600">{customError}</div>}
          <div>
            <a
              href="http://hdrhistogram.github.io/HdrHistogram/"
              target="_blank"
              rel="noopener noreferrer"
              className="text-xs text-blue-600 hover:underline"
            >
              Custom percentiles are calculated using HdrHistogram
            </a>
          </div>
        </div>
      )}

      {/* Chart */}
      <div className="rounded-lg border bg-white p-4">
        {chartData && chartData.dataSeries.length > 0 ? (
          <TimeSeriesChart
            dataSeries={chartData.dataSeries}
            chartFrom={txn.range.chartFrom}
            chartTo={txn.range.chartTo}
            dataPointIntervalMillis={chartData.dataPointIntervalMillis}
            markings={chartData.markings}
            stacked={false}
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

      {merged && merged.percentileValues && merged.percentileValues.length > 0 && (
        <div className="rounded-lg border bg-white p-4">
          <table className="text-sm">
            <tbody>
              {merged.percentileValues.map((pv) => (
                <tr key={pv.dataSeriesName}>
                  <td className="py-0.5 pr-4 text-gray-500">{pv.dataSeriesName}:</td>
                  <td className="py-0.5 font-medium tabular-nums">
                    {formatMillis(pv.value / 1000000)} ms
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
