/*
 * ErrorMessagesPage — error rate chart + error message list.
 *
 * Backend: GET /backend/error/messages
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTransaction } from '../../contexts/TransactionContext'
import { useAgent } from '../../contexts/AgentContext'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet } from '../../lib/api'
import { gtParseIncludesExcludes } from '../../lib/parser'
import { TimeSeriesChart, type PlotSeries } from '../../components/chart'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface ErrorMessage {
  message: string
  count: number
}

interface ErrorMessagesResponse {
  dataSeries: { data: Array<[number, number | null] | null> }
  dataSeriesExtra: Record<number, [number, number]>
  dataPointIntervalMillis: number
  errorMessages: ErrorMessage[]
  moreErrorMessagesAvailable: boolean
}

export function ErrorMessagesPage() {
  const txn = useTransaction()
  const { layout } = useLayout()
  const { agentRollupId } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()

  const urlFilter = searchParams.get('filter') || ''
  const [filter, setFilter] = useState(urlFilter)
  const [appliedFilter, setAppliedFilter] = useState(urlFilter)
  const [errorMessageLimit, setErrorMessageLimit] = useState(25)
  const [errorMessages, setErrorMessages] = useState<ErrorMessage[]>([])
  const [moreAvailable, setMoreAvailable] = useState(false)
  const [dataSeriesExtra, setDataSeriesExtra] = useState<Record<number, [number, number]>>({})
  const [chartSeries, setChartSeries] = useState<Array<{ name: string; data: Array<[number, number | null] | null> }>>([])
  const [dataPointIntervalMillis, setDataPointIntervalMillis] = useState(0)
  const [noData, setNoData] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [parsingError, setParsingError] = useState('')

  const fetchIdRef = useRef(0)

  useEffect(() => {
    setFilter(urlFilter)
    setAppliedFilter(urlFilter)
  }, [urlFilter])

  const fetchData = useCallback(
    (filterToUse = appliedFilter) => {
      if (agentRollupId === null || !txn.transactionType) return
      let parseResult: { includes: string[]; excludes: string[] }
      try {
        parseResult = gtParseIncludesExcludes(filterToUse)
      } catch (err) {
        setParsingError(err instanceof Error ? err.message : String(err))
        setLoading(false)
        return
      }
      setParsingError('')

      const fetchId = ++fetchIdRef.current
      setLoading(true)
      setError(null)

      const query: Record<string, string | string[] | number> = {
        'agent-rollup-id': agentRollupId,
        'transaction-type': txn.transactionType,
        from: txn.range.chartFrom,
        to: txn.range.chartTo,
        'error-message-limit': errorMessageLimit,
      }
      if (txn.transactionName) {
        query['transaction-name'] = txn.transactionName
      }
      if (parseResult.includes.length) {
        query['include'] = parseResult.includes
      }
      if (parseResult.excludes.length) {
        query['exclude'] = parseResult.excludes
      }

      apiGet<ErrorMessagesResponse>('/backend/error/messages', query)
        .then((data) => {
          if (fetchId !== fetchIdRef.current) return
          setNoData(!data.dataSeries.data.length)
          setChartSeries([{ name: 'Error rate', data: data.dataSeries.data }])
          setDataPointIntervalMillis(data.dataPointIntervalMillis)
          setDataSeriesExtra(data.dataSeriesExtra || {})
          setErrorMessages(data.errorMessages || [])
          setMoreAvailable(!!data.moreErrorMessagesAvailable)
        })
        .catch((e) => {
          if (fetchId !== fetchIdRef.current) return
          setError(e)
        })
        .finally(() => {
          if (fetchId === fetchIdRef.current) setLoading(false)
        })
    },
    [
      agentRollupId,
      txn.transactionType,
      txn.transactionName,
      txn.range.chartFrom,
      txn.range.chartTo,
      appliedFilter,
      errorMessageLimit,
    ]
  )

  useEffect(() => {
    fetchData()
  }, [fetchData, txn.refreshCounter])

  useEffect(() => {
    if (txn.range.last <= 0 || layout.offlineViewer) return
    const timer = setInterval(() => {
      txn.rangeActions.refresh()
    }, 60_000)
    return () => clearInterval(timer)
  }, [txn.range.last, layout.offlineViewer, txn.rangeActions])

  function applyFilterAndRefresh(nextFilter = filter) {
    setAppliedFilter(nextFilter)
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      if (nextFilter) {
        next.set('filter', nextFilter)
      } else {
        next.delete('filter')
      }
      return next
    })
    fetchData(nextFilter)
  }

  function buildSeeTracesHref(message: string): string {
    const baseQs = txn.buildTabQueryString()
    const params = new URLSearchParams(baseQs.startsWith('?') ? baseQs.slice(1) : baseQs)
    if (message.length <= 1000) {
      params.set('error-message-comparator', 'equals')
      params.set('error-message', message)
    } else {
      params.set('error-message-comparator', 'begins')
      params.set('error-message', message.substring(0, 1000))
    }
    return `/modern/error/traces?${params.toString()}`
  }

  const tooltipContent = useCallback(
    (
      from: number,
      to: number | null,
      dataIndex: number,
      _seriesIndex: number,
      allSeries: PlotSeries[]
    ) => {
      const val = allSeries[0]?.data[dataIndex]?.[1]
      const xval = allSeries[0]?.data[dataIndex]?.[0]
      if (val === 0 && xval !== undefined && !dataSeriesExtra[xval]) {
        return 'No errors'
      }

      function timeStr(ms: number): string {
        return new Date(ms).toLocaleTimeString(undefined, {
          hour: 'numeric',
          minute: '2-digit',
        })
      }

      let html = `<div style="font-weight:600;margin-bottom:2px">${timeStr(from)}`
      if (to) html += ` to ${timeStr(to)}`
      html += '</div>'
      if (val != null) {
        html += `<div>Error rate: ${val.toFixed(1)} %</div>`
      }
      if (xval !== undefined && dataSeriesExtra[xval]) {
        html += `<div>Error count: ${dataSeriesExtra[xval][0]}</div>`
        html += `<div>Transaction count: ${dataSeriesExtra[xval][1]}</div>`
      }
      return html
    },
    [dataSeriesExtra]
  )

  if (loading && chartSeries.length === 0 && !parsingError) return <PageSpinner />
  if (error && chartSeries.length === 0) return <HttpError error={error} />

  return (
    <div className="space-y-4">
      {/* Filter */}
      <div className="flex items-center gap-2">
        <input
          className="rounded border px-2 py-1 text-sm w-64"
          placeholder="Filter error messages (e.g., Timeout -expected)"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') applyFilterAndRefresh(filter)
          }}
        />
        <button
          className="rounded bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-700"
          onClick={() => applyFilterAndRefresh(filter)}
        >
          Refresh
        </button>
        {(filter || appliedFilter) && (
          <button
            className="text-xs text-gray-500 hover:text-gray-700"
            onClick={() => {
              setFilter('')
              applyFilterAndRefresh('')
            }}
          >
            Clear
          </button>
        )}
      </div>

      {parsingError && (
        <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {parsingError}
        </div>
      )}

      {/* Chart */}
      <div className="rounded-lg border bg-white p-4">
        {chartSeries.length > 0 && !noData ? (
          <TimeSeriesChart
            dataSeries={chartSeries}
            chartFrom={txn.range.chartFrom}
            chartTo={txn.range.chartTo}
            dataPointIntervalMillis={dataPointIntervalMillis}
            stacked={false}
            yAxisLabel="error rate (%)"
            yMax={100}
            tooltipContent={tooltipContent}
            onZoom={txn.rangeActions.handleZoom}
            onSelection={txn.rangeActions.handleSelection}
          />
        ) : (
          <div className="flex items-center justify-center h-[300px] text-sm text-gray-500">
            {noData ? 'No errors for selected time range' : 'Loading...'}
          </div>
        )}
      </div>

      {/* Error messages list */}
      {errorMessages.length > 0 && (
        <div className="rounded-lg border bg-white">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-gray-500 bg-gray-50">
                <th className="py-2 px-3 font-normal">Error message</th>
                <th className="py-2 px-3 font-normal text-right w-24">Count</th>
                <th className="py-2 px-3 font-normal text-right w-28"></th>
              </tr>
            </thead>
            <tbody>
              {errorMessages.map((em, i) => (
                <tr key={i} className="border-b last:border-b-0 hover:bg-gray-50">
                  <td className="py-2 px-3 break-all">{em.message}</td>
                  <td className="py-2 px-3 text-right tabular-nums">
                    {em.count.toLocaleString()}
                  </td>
                  <td className="py-2 px-3 text-right whitespace-nowrap">
                    <Link
                      to={buildSeeTracesHref(em.message)}
                      className="text-blue-600 hover:underline text-xs"
                    >
                      see traces
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {moreAvailable && (
            <button
              className="w-full py-2 text-sm text-blue-600 hover:bg-gray-50 border-t"
              onClick={() => setErrorMessageLimit((prev) => prev * 2)}
            >
              Show more error messages
            </button>
          )}
        </div>
      )}
    </div>
  )
}
