/*
 * Incidents page — displays currently open and recently resolved (last 30 days) alert incidents.
 *
 * Replaces incidents.js & incidents.html.
 */

import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useLayout } from '../contexts/LayoutContext'
import { apiGet } from '../lib/api'
import { formatDuration } from '../lib/formatting'
import { PageSpinner } from '../components/shared/Spinner'
import { HttpError } from '../components/shared/HttpError'

interface DisplayedIncident {
  openTime: number
  resolveTime?: number
  severity: string
  conditionType: 'metric' | 'synthetic-monitor' | 'heartbeat' | string
  display: string
  durationMillis: number
  agentRollupId: string
  agentRollupDisplay?: string
  metric?: string
  transactionType?: string
  transactionName?: string
  percentile?: number
  timePeriodSeconds?: number
  syntheticMonitorId?: string
  thresholdMillis?: number
}

interface IncidentsResponse {
  openIncidents: DisplayedIncident[]
  resolvedIncidents: DisplayedIncident[]
}

function buildIncidentHref(
  incident: DisplayedIncident,
  resolved: boolean,
  isCentral: boolean
): string | null {
  const params = new URLSearchParams()
  if (isCentral && incident.agentRollupId) {
    if (incident.agentRollupId.endsWith('::')) {
      params.set('agent-rollup-id', incident.agentRollupId)
    } else {
      params.set('agent-id', incident.agentRollupId)
    }
  }

  const halfHourInMillis = 1800000
  const fiveMinutesInMillis = 300000

  let timeRangeFrom: number | undefined
  let timeRangeTo: number | undefined
  let timeRangeLast: number | undefined

  if (resolved && incident.resolveTime !== undefined) {
    let from = incident.openTime
    let to = incident.resolveTime
    if (incident.conditionType === 'metric' || incident.conditionType === 'heartbeat') {
      from -= (incident.timePeriodSeconds ?? 0) * 1000
    } else if (incident.conditionType === 'synthetic-monitor') {
      from -= incident.thresholdMillis ?? 0
    }
    from -= fiveMinutesInMillis
    to += fiveMinutesInMillis
    timeRangeFrom = Math.floor(from / halfHourInMillis) * halfHourInMillis
    timeRangeTo = Math.ceil(to / halfHourInMillis) * halfHourInMillis
  } else {
    let last = Date.now() - incident.openTime
    if (incident.conditionType === 'metric' || incident.conditionType === 'heartbeat') {
      last += (incident.timePeriodSeconds ?? 0) * 1000
    } else if (incident.conditionType === 'synthetic-monitor') {
      last += incident.thresholdMillis ?? 0
    }
    last += fiveMinutesInMillis
    timeRangeLast = Math.ceil(last / halfHourInMillis) * halfHourInMillis
  }

  const appendTimeRange = () => {
    if (timeRangeFrom !== undefined && timeRangeTo !== undefined) {
      params.set('from', String(timeRangeFrom))
      params.set('to', String(timeRangeTo))
    } else if (timeRangeLast !== undefined) {
      params.set('last', String(timeRangeLast))
    }
  }

  if (incident.conditionType === 'metric') {
    const metric = incident.metric ?? ''
    if (metric === 'transaction:x-percentile') {
      if (incident.transactionType) params.set('transaction-type', incident.transactionType)
      if (incident.transactionName) params.set('transaction-name', incident.transactionName)
      appendTimeRange()
      if (incident.percentile !== undefined) {
        params.set('percentile', String(incident.percentile))
      }
      return `/modern/transaction/percentiles?${params.toString()}`
    }
    if (metric === 'transaction:average') {
      if (incident.transactionType) params.set('transaction-type', incident.transactionType)
      if (incident.transactionName) params.set('transaction-name', incident.transactionName)
      appendTimeRange()
      return `/modern/transaction/average?${params.toString()}`
    }
    if (metric === 'transaction:count') {
      if (incident.transactionType) params.set('transaction-type', incident.transactionType)
      if (incident.transactionName) params.set('transaction-name', incident.transactionName)
      appendTimeRange()
      return `/modern/transaction/throughput?${params.toString()}`
    }
    if (metric === 'error:rate') {
      if (incident.transactionType) params.set('transaction-type', incident.transactionType)
      if (incident.transactionName) params.set('transaction-name', incident.transactionName)
      appendTimeRange()
      params.set('summary-sort-order', 'error-rate')
      return `/modern/error/messages?${params.toString()}`
    }
    if (metric === 'error:count') {
      if (incident.transactionType) params.set('transaction-type', incident.transactionType)
      if (incident.transactionName) params.set('transaction-name', incident.transactionName)
      appendTimeRange()
      return `/modern/error/messages?${params.toString()}`
    }
    if (metric.startsWith('gauge:')) {
      params.set('gauge-name', metric.slice('gauge:'.length))
      appendTimeRange()
      return `/modern/jvm/gauges?${params.toString()}`
    }
    return null
  }

  if (incident.conditionType === 'synthetic-monitor') {
    if (incident.syntheticMonitorId) {
      params.set('synthetic-monitor-id', incident.syntheticMonitorId)
    }
    appendTimeRange()
    return `/modern/synthetic-monitors?${params.toString()}`
  }

  if (incident.conditionType === 'heartbeat') {
    appendTimeRange()
    return `/modern/jvm/gauges?${params.toString()}`
  }

  return null
}

export function IncidentsPage() {
  const { layout } = useLayout()
  const [data, setData] = useState<IncidentsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    document.title = 'Incidents \u00b7 Glowroot'
  }, [])

  const fetchIncidents = useCallback(async (autoRefresh: boolean) => {
    try {
      const resp = await apiGet<IncidentsResponse>('/backend/incidents', {
        'auto-refresh': autoRefresh ? 'true' : undefined,
      })
      setData(resp)
      setError(null)
    } catch (err) {
      setError(err)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchIncidents(false)
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'hidden') {
        fetchIncidents(true)
      }
    }, 30000)
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        fetchIncidents(false)
      }
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [fetchIncidents])

  const embeddedDisplay =
    layout.embeddedAgentRollup?.topLevelDisplay || layout.embeddedAgentRollup?.lastDisplayPart || ''

  return (
    <div className="space-y-6">
      <div>
        {embeddedDisplay ? (
          <h1 className="gt-header-text text-2xl font-bold text-[#8e6c15]">
            {embeddedDisplay}
            <span className="mx-2 text-gray-400">|</span>
            Incidents
          </h1>
        ) : (
          <h1 className="gt-header-text text-2xl font-bold text-[#8e6c15]">Incidents</h1>
        )}
      </div>

      {/* Open incidents card */}
      <div className="gt-incident-active rounded-lg border border-[#bf380b] bg-white overflow-hidden">
        <div className="border-b border-[#bf380b] bg-[#f4e4e4] px-5 py-3 text-[#555]">
          <h2 className="text-lg font-semibold text-gray-900">Open</h2>
        </div>
        <div className="p-4">
          {loading && !data && <PageSpinner />}
          {error != null && <HttpError error={error} />}
          {data && (
            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse table-fixed">
                <thead>
                  <tr className="border-b text-xs font-medium uppercase tracking-wider text-gray-500">
                    <th scope="col" className="py-2 pr-3 w-[105px]">Severity</th>
                    <th scope="col" className="py-2 pr-3 w-[110px]">Duration</th>
                    {layout.central && (
                      <th scope="col" className="py-2 pr-3 w-[180px]">Agent</th>
                    )}
                    <th scope="col" className="py-2">Condition</th>
                  </tr>
                </thead>
                <tbody className="divide-y">
                  {data.openIncidents.length === 0 ? (
                    <tr>
                      <td
                        colSpan={layout.central ? 4 : 3}
                        className="py-4 text-sm text-gray-500"
                      >
                        No open incidents
                      </td>
                    </tr>
                  ) : (
                    data.openIncidents.map((inc, idx) => {
                      const href = buildIncidentHref(inc, false, layout.central)
                      return (
                        <tr
                          key={`${inc.openTime}-${inc.display}-${idx}`}
                          className="text-base font-semibold text-red-600"
                        >
                          <td className="py-2.5 pr-3 align-top">{inc.severity}</td>
                          <td className="py-2.5 pr-3 align-top">
                            {formatDuration(inc.durationMillis)}
                          </td>
                          {layout.central && (
                            <td className="py-2.5 pr-3 align-top break-words">
                              {inc.agentRollupDisplay}
                            </td>
                          )}
                          <td className="py-2.5 align-top break-words">
                            {href ? (
                              <Link to={href} className="text-red-600 hover:underline">
                                {inc.display}
                              </Link>
                            ) : (
                              <span>{inc.display}</span>
                            )}
                          </td>
                        </tr>
                      )
                    })
                  )}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Resolved incidents card */}
      {data && (
        <div className="rounded-lg border border-[#d2ba7f] bg-white overflow-hidden">
          <div className="flex items-baseline justify-between border-b border-[#d2ba7f] bg-[#edeae4] px-5 py-3 text-[#555]">
            <h2 className="text-lg font-semibold text-gray-900">Resolved</h2>
            <span className="text-base text-gray-500">(last 30 days)</span>
          </div>
          <div className="p-4">
            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse table-fixed">
                <thead>
                  <tr className="border-b text-xs font-medium uppercase tracking-wider text-gray-500">
                    <th scope="col" className="py-2 pr-3 w-[105px]">Severity</th>
                    <th scope="col" className="py-2 pr-3 w-[220px]">Resolved</th>
                    <th scope="col" className="py-2 pr-3 w-[110px]">Duration</th>
                    {layout.central && (
                      <th scope="col" className="py-2 pr-3 w-[180px]">Agent</th>
                    )}
                    <th scope="col" className="py-2">Condition</th>
                  </tr>
                </thead>
                <tbody className="divide-y">
                  {data.resolvedIncidents.length === 0 ? (
                    <tr>
                      <td
                        colSpan={layout.central ? 5 : 4}
                        className="py-4 text-sm text-gray-500"
                      >
                        No resolved incidents
                      </td>
                    </tr>
                  ) : (
                    data.resolvedIncidents.map((inc, idx) => {
                      const href = buildIncidentHref(inc, true, layout.central)
                      return (
                        <tr
                          key={`${inc.openTime}-${inc.resolveTime}-${idx}`}
                          className="text-sm text-gray-800"
                        >
                          <td className="py-2.5 pr-3 align-top">{inc.severity}</td>
                          <td className="py-2.5 pr-3 align-top">
                            {inc.resolveTime !== undefined
                              ? new Date(inc.resolveTime).toLocaleString()
                              : ''}
                          </td>
                          <td className="py-2.5 pr-3 align-top">
                            {formatDuration(inc.durationMillis)}
                          </td>
                          {layout.central && (
                            <td className="py-2.5 pr-3 align-top break-words">
                              {inc.agentRollupDisplay}
                            </td>
                          )}
                          <td className="py-2.5 align-top break-words">
                            {href ? (
                              <Link to={href} className="text-blue-600 hover:underline">
                                {inc.display}
                              </Link>
                            ) : (
                              <span>{inc.display}</span>
                            )}
                          </td>
                        </tr>
                      )
                    })
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
