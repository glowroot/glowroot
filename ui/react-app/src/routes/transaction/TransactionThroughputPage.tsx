/*
 * TransactionThroughputPage — single line chart of transactions per minute.
 *
 * Backend: GET /backend/transaction/throughput
 */

import { useCallback } from 'react'
import { useTransaction } from '../../contexts/TransactionContext'
import { useChartData } from '../../hooks/useChartData'
import { TimeSeriesChart, type PlotSeries } from '../../components/chart'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { formatMillis } from '../../lib/formatting'
import { ResponseTimeSubNav } from './ResponseTimeSubNav'

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
      <ResponseTimeSubNav
        active="throughput"
        basePath={basePath}
        qs={qs}
        onRefreshActive={txn.rangeActions.refresh}
        onZoomOut={txn.rangeActions.zoomOut}
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
