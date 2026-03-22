/*
 * TransactionThroughputPage — single line chart of transactions per minute.
 *
 * Backend: GET /backend/transaction/throughput
 */

import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { RefreshCw, ZoomOut } from 'lucide-react'
import { useTransaction } from '../../contexts/TransactionContext'
import { useChartData } from '../../hooks/useChartData'
import { TimeSeriesChart, type PlotSeries } from '../../components/chart'
import { HelpPopover } from '../../components/shared/HelpPopover'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { formatMillis } from '../../lib/formatting'

interface ThroughputExtra {
  transactionCount: number
  transactionsPerMin: number
}

export function TransactionThroughputPage() {
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
    (data: Record<string, unknown>): ThroughputExtra => ({
      transactionCount: (data.transactionCount as number) || 0,
      transactionsPerMin: (data.transactionsPerMin as number) || 0,
    }),
    []
  )

  const { chartData, loading, noData, error } = useChartData<ThroughputExtra>({
    url: '/backend/transaction/throughput',
    range: txn.range,
    refreshCounter: txn.refreshCounter,
    addToQuery,
    extractExtra,
  })

  const tooltipContent = useCallback(
    (
      from: number,
      to: number | null,
      dataIndex: number,
      _seriesIndex: number,
      allSeries: PlotSeries[]
    ) => {
      const val = allSeries[0]?.data[dataIndex]?.[1]
      if (val == null || val === 0) return 'No data'

      function smartFormat(ms: number): string {
        return new Date(ms).toLocaleTimeString(
          undefined,
          ms % 60000 === 0
            ? { hour: 'numeric', minute: '2-digit' }
            : { hour: 'numeric', minute: '2-digit', second: '2-digit' }
        )
      }

      let html = `<div style="font-weight:600;margin-bottom:2px">${smartFormat(from)}`
      if (to) html += ` to ${smartFormat(to)}`
      html += '</div>'
      html += `<div>${formatMillis(val)} transactions per minute</div>`
      return html
    },
    []
  )

  const qs = txn.buildTabQueryString()
  const basePath = `/modern/${txn.shortName}`
  const extra = chartData?.extra

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
          <Link to={`${basePath}/percentiles${qs}`} className="text-gray-500 hover:text-gray-700">
            Percentiles
          </Link>
          <Link
            to={`${basePath}/throughput${qs}`}
            onClick={(e) => {
              e.preventDefault()
              txn.rangeActions.refresh()
            }}
            className="font-medium text-blue-600 no-underline"
          >
            Throughput
          </Link>
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
            yAxisLabel="transactions per minute"
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

      {extra && extra.transactionCount > 0 && (
        <div className="rounded-lg border bg-white p-4 text-sm text-gray-700">
          Throughput:{' '}
          <span className="font-medium tabular-nums">
            {formatMillis(extra.transactionsPerMin)} transactions per minute
          </span>{' '}
          ({extra.transactionCount.toLocaleString()}{' '}
          {extra.transactionCount === 1 ? 'transaction' : 'transactions'} in this period)
        </div>
      )}
    </div>
  )
}
