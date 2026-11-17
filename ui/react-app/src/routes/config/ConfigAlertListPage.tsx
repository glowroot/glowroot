import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost } from '../../lib/api'
import { formatDuration } from '../../lib/formatting'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface AlertSummary {
  version: string
  display: string
}

interface AlertListData {
  alerts: AlertSummary[]
  disabledForNextMillis?: number | null
}

const UNIT_MULTIPLIER: Record<string, number> = {
  minutes: 60 * 1000,
  hours: 60 * 60 * 1000,
  days: 24 * 60 * 60 * 1000,
}

export function ConfigAlertListPage() {
  const { agentRollupId, agentRollup, agentQueryString } = useAgent()
  const { layout } = useLayout()
  const [data, setData] = useState<AlertListData | null>(null)
  const [disabledForNextMillis, setDisabledForNextMillis] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [showDisableModal, setShowDisableModal] = useState(false)
  const [disableValue, setDisableValue] = useState('1')
  const [disableUnit, setDisableUnit] = useState<'minutes' | 'hours' | 'days'>('hours')
  const [actionBusy, setActionBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.alerts ?? false

  function onNewData(resp: AlertListData) {
    setData(resp)
    if (resp.disabledForNextMillis != null && resp.disabledForNextMillis > 0) {
      setDisabledForNextMillis(Math.floor(resp.disabledForNextMillis / 1000) * 1000)
    } else {
      setDisabledForNextMillis(null)
    }
  }

  const load = useCallback(async () => {
    if (agentRollupId === null) return
    try {
      const resp = await apiGet<AlertListData>('/backend/config/alerts', {
        'agent-rollup-id': agentRollupId,
      })
      onNewData(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentRollupId])

  useEffect(() => { load() }, [load])

  const isDisabledCountdownActive =
    !layout?.offlineViewer && disabledForNextMillis !== null && disabledForNextMillis > 0

  useEffect(() => {
    if (!isDisabledCountdownActive) return
    const timer = setInterval(() => {
      setDisabledForNextMillis((prev) => {
        if (prev === null || prev <= 1000) return null
        return prev - 1000
      })
    }, 1000)
    return () => clearInterval(timer)
  }, [isDisabledCountdownActive])

  async function handleDisableAlerting() {
    if (agentRollupId === null) return
    const num = Number(disableValue)
    if (!disableValue.trim() || !Number.isFinite(num) || num <= 0) {
      setActionError('Please enter a positive duration')
      return
    }
    setActionBusy(true)
    setActionError(null)
    try {
      const millis = Math.round(num * UNIT_MULTIPLIER[disableUnit])
      const resp = await apiPost<AlertListData>(
        '/backend/config/disable-alerting?agent-rollup-id=' + encodeURIComponent(agentRollupId),
        { disableForNextMillis: millis }
      )
      onNewData(resp)
      setShowDisableModal(false)
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Failed to disable alerting')
    } finally {
      setActionBusy(false)
    }
  }

  async function handleReEnableAlerting() {
    if (agentRollupId === null) return
    setActionBusy(true)
    setActionError(null)
    try {
      const resp = await apiPost<AlertListData>(
        '/backend/config/re-enable-alerting?agent-rollup-id=' + encodeURIComponent(agentRollupId),
        { disableForNextMillis: null }
      )
      onNewData(resp)
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Failed to re-enable alerting')
    } finally {
      setActionBusy(false)
    }
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!data) return null

  const qs = agentQueryString()
  const isTemporarilyDisabled =
    !layout?.offlineViewer && disabledForNextMillis != null && disabledForNextMillis > 0

  return (
    <SettingsPage title="Alerts" wide>
      {isTemporarilyDisabled && disabledForNextMillis != null && (
        <p className="text-sm text-amber-600">
          Alerting is disabled for the next {formatDuration(disabledForNextMillis)}
        </p>
      )}
      {actionError && !showDisableModal && (
        <div className="rounded-md border border-red-300 bg-red-50 px-4 py-2 text-sm text-red-700">
          {actionError}
        </div>
      )}
      <div className="divide-y divide-gray-100 border-t border-gray-100">
        {data.alerts.map((alert) => (
          <Link
            key={alert.version}
            to={`/modern/config/alert${qs}${qs ? '&' : '?'}v=${encodeURIComponent(alert.version)}`}
            className="flex items-center py-3 text-sm text-gray-900 no-underline hover:bg-gray-50/80 -mx-1 px-1 rounded"
          >
            {alert.display}
          </Link>
        ))}
        {data.alerts.length === 0 && (
          <p className="py-3 text-sm text-gray-500">No alerts configured.</p>
        )}
      </div>
      {canEdit && (
        <div className="flex flex-wrap items-center gap-3">
          <Link
            to={`/modern/config/alert${qs}${qs ? '&' : '?'}new`}
            className="inline-flex items-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 no-underline"
          >
            Add new
          </Link>
          {!layout?.offlineViewer && (
            <>
              <button
                type="button"
                disabled={actionBusy}
                onClick={() => {
                  setActionError(null)
                  setShowDisableModal(true)
                }}
                className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                {isTemporarilyDisabled ? 'Change alerting disabled time' : 'Temporarily disable alerting'}
              </button>
              {isTemporarilyDisabled && (
                <button
                  type="button"
                  disabled={actionBusy}
                  onClick={handleReEnableAlerting}
                  className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                >
                  Re-enable alerting
                </button>
              )}
            </>
          )}
        </div>
      )}
      {showDisableModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
            <h3 className="mb-4 text-lg font-medium">
              {isTemporarilyDisabled ? 'Change alerting disabled time' : 'Temporarily disable alerting'}
            </h3>
            <div className="mb-4 flex items-center gap-2">
              <span className="text-sm text-gray-700">Disable alerting for the next</span>
              <input
                type="number"
                min={1}
                value={disableValue}
                onChange={(e) => setDisableValue(e.target.value)}
                className="w-24 rounded-md border border-gray-300 px-2 py-1 text-sm"
              />
              <select
                value={disableUnit}
                onChange={(e) => setDisableUnit(e.target.value as 'minutes' | 'hours' | 'days')}
                className="rounded-md border border-gray-300 bg-white px-2 py-1 text-sm"
              >
                <option value="minutes">minutes</option>
                <option value="hours">hours</option>
                <option value="days">days</option>
              </select>
            </div>
            {actionError && <p className="mb-3 text-sm text-red-600">{actionError}</p>}
            <div className="flex justify-end gap-2">
              <button
                type="button"
                disabled={actionBusy}
                onClick={() => setShowDisableModal(false)}
                className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={actionBusy}
                onClick={handleDisableAlerting}
                className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {actionBusy ? 'Saving...' : 'Ok'}
              </button>
            </div>
          </div>
        </div>
      )}
    </SettingsPage>
  )
}
