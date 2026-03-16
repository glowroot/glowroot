import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost } from '../../lib/api'
import { PATTERNS } from '../../lib/validation'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Checkbox } from '../../components/ui/checkbox'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface MetricOption {
  id: string
  display: string
  heading?: boolean
  disabled?: boolean
}

const BASE_METRICS: MetricOption[] = [
  { id: 'transaction', display: 'Transactions', heading: true, disabled: true },
  { id: 'transaction:average', display: 'Response time (average)' },
  { id: 'transaction:x-percentile', display: 'Response time (X\u1d57\u02b0 percentile)' },
  { id: 'transaction:timer-inclusive', display: 'Breakdown metric time (inclusive)' },
  { id: 'transaction:timer-exclusive', display: 'Breakdown metric time (exclusive)' },
  { id: 'transaction:timer-count', display: 'Breakdown metric count' },
  { id: 'transaction:count', display: 'Transaction count' },
  { id: '-empty1-', display: '', disabled: true },
  { id: 'error', display: 'Errors', heading: true, disabled: true },
  { id: 'error:rate', display: 'Error rate (%)' },
  { id: 'error:count', display: 'Error count' },
  { id: '-empty2-', display: '', disabled: true },
]

interface GaugeInfo {
  name: string
  display: string
  unit?: string
}

interface SyntheticMonitorInfo {
  id: string
  display: string
}

interface PagerDutyKeyInfo {
  key: string
  display: string
}

interface SlackWebhookInfo {
  id: string
  display: string
}

interface AlertCondition {
  conditionType: 'metric' | 'synthetic-monitor' | 'heartbeat'
  metric?: string
  transactionType?: string
  transactionName?: string
  percentile?: number
  errorMessageFilter?: string
  threshold?: number
  lowerBoundThreshold?: boolean
  timePeriodSeconds?: number
  minTransactionCount?: number
  syntheticMonitorId?: string
  thresholdMillis?: number
  consecutiveCount?: number
}

interface EmailNotification {
  emailAddresses: string[]
}

interface PagerDutyNotification {
  pagerDutyIntegrationKey: string
}

interface SlackNotification {
  slackWebhookId: string
  slackChannels: string[]
}

interface AlertConfig {
  condition: AlertCondition
  severity?: string
  emailNotification?: EmailNotification
  pagerDutyNotification?: PagerDutyNotification
  slackNotification?: SlackNotification
  version?: string
}

interface AlertData {
  config: AlertConfig
  heading?: string
  gauges: GaugeInfo[]
  syntheticMonitors: SyntheticMonitorInfo[]
  pagerDutyIntegrationKeys: PagerDutyKeyInfo[]
  slackWebhooks: SlackWebhookInfo[]
}

const BYTE_UNITS = [
  { value: 'bytes', display: 'bytes', multiplier: 1 },
  { value: 'KB', display: 'KB', multiplier: 1024 },
  { value: 'MB', display: 'MB', multiplier: 1024 * 1024 },
  { value: 'GB', display: 'GB', multiplier: 1024 * 1024 * 1024 },
]

function thresholdToDisplay(threshold: number | undefined, unit: string): { value: number | undefined; byteUnit: string } {
  if (threshold === undefined || threshold === null) return { value: undefined, byteUnit: 'MB' }
  if (unit !== ' bytes') return { value: threshold, byteUnit: '' }
  if (threshold === 0) return { value: 0, byteUnit: 'bytes' }
  if (threshold % (1024 * 1024 * 1024) === 0) return { value: threshold / (1024 * 1024 * 1024), byteUnit: 'GB' }
  if (threshold % (1024 * 1024) === 0) return { value: threshold / (1024 * 1024), byteUnit: 'MB' }
  if (threshold % 1024 === 0) return { value: threshold / 1024, byteUnit: 'KB' }
  return { value: threshold, byteUnit: 'bytes' }
}

function displayToThreshold(displayValue: number | undefined, byteUnit: string): number | undefined {
  if (displayValue === undefined) return undefined
  const found = BYTE_UNITS.find((u) => u.value === byteUnit)
  return found ? displayValue * found.multiplier : displayValue
}

export function ConfigAlertPage() {
  const { agentRollupId, agentRollup, agentQueryString } = useAgent()
  const { layout } = useLayout()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const version = searchParams.get('v')
  const isNew = !version

  const [config, setConfig] = useState<AlertConfig | null>(null)
  const [originalConfig, setOriginalConfig] = useState<AlertConfig | null>(null)
  const [heading, setHeading] = useState('<New>')
  const [gauges, setGauges] = useState<GaugeInfo[]>([])
  const [gaugeUnits, setGaugeUnits] = useState<Record<string, string>>({})
  const [syntheticMonitors, setSyntheticMonitors] = useState<SyntheticMonitorInfo[]>([])
  const [pagerDutyIntegrationKeys, setPagerDutyIntegrationKeys] = useState<PagerDutyKeyInfo[]>([])
  const [slackWebhooks, setSlackWebhooks] = useState<SlackWebhookInfo[]>([])

  // Display-level state for threshold (may differ from config due to byte unit conversion)
  const [displayThreshold, setDisplayThreshold] = useState<number | undefined>()
  const [byteUnit, setByteUnit] = useState('MB')
  const [timePeriodMinutes, setTimePeriodMinutes] = useState<number | undefined>()
  const [emailAddressesStr, setEmailAddressesStr] = useState('')
  const [slackChannelsStr, setSlackChannelsStr] = useState('')

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.alerts ?? false

  function processData(data: AlertData) {
    // Ensure notification objects exist
    const cfg = { ...data.config }
    if (!cfg.emailNotification) cfg.emailNotification = { emailAddresses: [] }
    if (!cfg.pagerDutyNotification) cfg.pagerDutyNotification = { pagerDutyIntegrationKey: '' }
    if (!cfg.slackNotification) cfg.slackNotification = { slackWebhookId: '', slackChannels: [] }

    setConfig(cfg)
    setOriginalConfig(JSON.parse(JSON.stringify(cfg)))
    setHeading(data.heading || '<New>')

    // Build gauge units map
    const units: Record<string, string> = {}
    for (const g of data.gauges) {
      units[g.name] = g.unit ? ' ' + g.unit : ''
    }
    setGaugeUnits(units)
    setGauges(data.gauges)
    setSyntheticMonitors(data.syntheticMonitors || [])
    setPagerDutyIntegrationKeys(data.pagerDutyIntegrationKeys || [])
    setSlackWebhooks(data.slackWebhooks || [])

    // Set display values
    if (data.heading) {
      if (cfg.condition.timePeriodSeconds !== undefined) {
        setTimePeriodMinutes(cfg.condition.timePeriodSeconds / 60)
      } else {
        setTimePeriodMinutes(undefined)
      }
      setEmailAddressesStr(cfg.emailNotification!.emailAddresses.join(', '))
      setSlackChannelsStr(cfg.slackNotification!.slackChannels.join(', '))
    }

    // Handle threshold display with byte units
    if (cfg.condition.conditionType === 'metric' && cfg.condition.metric?.startsWith('gauge:')) {
      const gaugeName = cfg.condition.metric.substring('gauge:'.length)
      const gaugeUnit = units[gaugeName]
      if (gaugeUnit === ' bytes') {
        const { value, byteUnit: bu } = thresholdToDisplay(cfg.condition.threshold, ' bytes')
        setDisplayThreshold(value)
        setByteUnit(bu)
      } else {
        setDisplayThreshold(cfg.condition.threshold)
        setByteUnit('')
      }
    } else {
      setDisplayThreshold(cfg.condition.threshold)
      setByteUnit('')
    }
  }

  const load = useCallback(async () => {
    if (agentRollupId === null) return
    try {
      if (version) {
        const resp = await apiGet<AlertData>('/backend/config/alerts', {
          'agent-rollup-id': agentRollupId,
          version,
        })
        processData(resp)
      } else {
        const resp = await apiGet<{
          gauges: GaugeInfo[]
          syntheticMonitors: SyntheticMonitorInfo[]
          pagerDutyIntegrationKeys: PagerDutyKeyInfo[]
          slackWebhooks: SlackWebhookInfo[]
        }>('/backend/config/alert-dropdowns', {
          'agent-rollup-id': agentRollupId,
          version: version ?? 'undefined',
        })
        processData({
          config: {
            condition: {
              conditionType: 'metric',
              metric: '',
            },
          },
          gauges: resp.gauges,
          syntheticMonitors: resp.syntheticMonitors,
          pagerDutyIntegrationKeys: resp.pagerDutyIntegrationKeys,
          slackWebhooks: resp.slackWebhooks,
        })
      }
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentRollupId, version])

  useEffect(() => { load() }, [load])

  // Derive unit from metric
  function getUnit(metric: string | undefined): string {
    if (!metric) return ''
    if (metric === 'transaction:average' || metric === 'transaction:x-percentile') return 'milliseconds'
    if (metric === 'error:rate') return 'percent'
    if (metric.startsWith('gauge:')) {
      const gaugeName = metric.substring('gauge:'.length)
      return gaugeUnits[gaugeName] ?? ''
    }
    return ''
  }

  function showTransactionTypeAndName(metric: string | undefined): boolean {
    return !!metric && (metric.startsWith('transaction:') || metric.startsWith('error:'))
  }

  function showMinTransactionCount(metric: string | undefined): boolean {
    return showTransactionTypeAndName(metric) && metric !== 'transaction:count' && metric !== 'error:count'
  }

  function phraseForValue(metric: string | undefined): string {
    if (metric === 'transaction:average') return 'average response time'
    if (metric === 'transaction:x-percentile') return 'X\u1d57\u02b0 percentile response time'
    if (metric === 'transaction:count') return 'transaction count'
    if (metric === 'error:rate') return 'error rate'
    if (metric === 'error:count') return 'error count'
    if (metric?.startsWith('gauge:')) return 'average gauge value'
    return 'value'
  }

  function updateConfig(updater: (c: AlertConfig) => AlertConfig) {
    setConfig((prev) => prev ? updater({ ...prev }) : prev)
  }

  function updateCondition(updates: Partial<AlertCondition>) {
    updateConfig((c) => ({ ...c, condition: { ...c.condition, ...updates } }))
  }

  function handleConditionTypeChange(conditionType: AlertCondition['conditionType']) {
    updateConfig((c) => ({
      ...c,
      condition: { conditionType },
    }))
    setTimePeriodMinutes(undefined)
  }

  function handleMetricChange(metric: string) {
    const prev = config?.condition.metric
    const newCondition = { ...config!.condition, metric }

    if (showTransactionTypeAndName(metric)) {
      if (newCondition.transactionType === undefined) {
        newCondition.transactionType = agentRollup?.defaultTransactionType || agentRollup?.transactionTypes?.[0] || ''
      }
      if (newCondition.transactionName === undefined) {
        newCondition.transactionName = ''
      }
    } else {
      delete newCondition.transactionType
      delete newCondition.transactionName
    }

    if (showMinTransactionCount(metric)) {
      if (newCondition.minTransactionCount === undefined) {
        newCondition.minTransactionCount = undefined
      }
    } else {
      delete newCondition.minTransactionCount
    }

    if (metric !== 'transaction:x-percentile') {
      delete newCondition.percentile
    }

    if (metric === 'error:count') {
      if (newCondition.errorMessageFilter === undefined) {
        newCondition.errorMessageFilter = ''
      }
    } else {
      delete newCondition.errorMessageFilter
    }

    // Clear threshold on metric change
    if (prev) {
      delete newCondition.threshold
      setDisplayThreshold(undefined)
    }

    // Update byte unit
    const unit = getUnit(metric)
    if (unit === ' bytes') {
      setByteUnit((prev) => prev || 'MB')
    } else {
      setByteUnit('')
    }

    setConfig((c) => c ? { ...c, condition: newCondition } : c)
  }

  function handleDisplayThresholdChange(val: string) {
    const num = val === '' ? undefined : Number(val)
    setDisplayThreshold(num)
    const unit = getUnit(config?.condition.metric)
    if (unit === ' bytes' && byteUnit) {
      updateCondition({ threshold: displayToThreshold(num, byteUnit) })
    } else {
      updateCondition({ threshold: num })
    }
  }

  function handleByteUnitChange(newUnit: string) {
    setByteUnit(newUnit)
    updateCondition({ threshold: displayToThreshold(displayThreshold, newUnit) })
  }

  function handleTimePeriodMinutesChange(val: string) {
    const num = val === '' ? undefined : Number(val)
    setTimePeriodMinutes(num)
    updateCondition({ timePeriodSeconds: num !== undefined ? num * 60 : undefined })
  }

  function handleEmailAddressesChange(val: string) {
    setEmailAddressesStr(val)
    const addresses = val.split(',').map((s) => s.trim()).filter(Boolean)
    updateConfig((c) => ({
      ...c,
      emailNotification: { ...c.emailNotification!, emailAddresses: addresses },
    }))
  }

  function handleSlackChannelsChange(val: string) {
    setSlackChannelsStr(val)
    const channels = val.split(',').map((s) => s.trim()).filter(Boolean)
    updateConfig((c) => ({
      ...c,
      slackNotification: { ...c.slackNotification!, slackChannels: channels },
    }))
  }

  const hasChanges = config && originalConfig && JSON.stringify(config) !== JSON.stringify(originalConfig)

  async function handleSave() {
    if (agentRollupId === null || !config) return
    const postData: AlertConfig = JSON.parse(JSON.stringify(config))
    // Strip empty notifications
    if (!postData.emailNotification?.emailAddresses.length) delete postData.emailNotification
    if (!postData.pagerDutyNotification?.pagerDutyIntegrationKey) delete postData.pagerDutyNotification
    if (!postData.slackNotification?.slackWebhookId) delete postData.slackNotification

    const endpoint = version
      ? '/backend/config/alerts/update'
      : '/backend/config/alerts/add'
    const resp = await apiPost<AlertData>(
      endpoint + '?agent-rollup-id=' + encodeURIComponent(agentRollupId),
      postData
    )
    processData(resp)
    if (!version) {
      const qs = agentQueryString()
      navigate(
        `/modern/config/alert${qs}${qs ? '&' : '?'}v=${encodeURIComponent(resp.config.version!)}`,
        { replace: true }
      )
    }
  }

  async function handleDelete() {
    if (agentRollupId === null || !config) return
    await apiPost(
      '/backend/config/alerts/remove?agent-rollup-id=' + encodeURIComponent(agentRollupId),
      { version: config.version }
    )
    const qs = agentQueryString()
    navigate(`/modern/config/alert-list${qs}`, { replace: true })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!config) return null

  const metric = config.condition.metric
  const unit = getUnit(metric)
  const isByteUnit = unit === ' bytes'

  // Build metrics list with gauges
  const metrics: MetricOption[] = [
    ...BASE_METRICS,
    ...gauges.map((g) => ({ id: 'gauge:' + g.name, display: g.display })),
  ]
  // If the current gauge metric is not in the list, add it as unavailable
  if (config.condition.conditionType === 'metric' && metric?.startsWith('gauge:')) {
    const gaugeName = metric.substring('gauge:'.length)
    if (!gauges.find((g) => g.name === gaugeName)) {
      metrics.push({ id: '-empty9-', display: '', disabled: true })
      metrics.push({ id: metric, display: gaugeName + ' (not available)', disabled: true })
    }
  }

  return (
    <div>
      <div className="mb-3">
        <Link
          to={'/modern/config/alert-list' + agentQueryString()}
          className="text-sm text-blue-600 hover:underline"
        >
          Return to list
        </Link>
      </div>
      <div className="rounded-lg border bg-white p-6">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">
          Alerts &mdash; {heading}
        </h2>
        {/* Condition */}
        <fieldset className="mb-6">
          <legend className="text-sm font-semibold text-gray-700 mb-3">Condition</legend>

          {layout.central && (
            <GtFormGroup label="Based on">
              <div className="space-y-1">
                {(['metric', 'synthetic-monitor', 'heartbeat'] as const).map((type) => (
                  <label key={type} className="flex items-center gap-2 text-sm">
                    <input
                      type="radio"
                      name="conditionType"
                      value={type}
                      checked={config.condition.conditionType === type}
                      onChange={() => handleConditionTypeChange(type)}
                      disabled={!canEdit}
                    />
                    {type === 'metric' ? 'Metric' : type === 'synthetic-monitor' ? 'Synthetic monitor' : 'Heartbeat'}
                  </label>
                ))}
              </div>
            </GtFormGroup>
          )}

          {/* Metric condition */}
          {config.condition.conditionType === 'metric' && (
            <>
              <GtFormGroup
                label="Metric"
                required
                value={metric || ''}
                helpText="The metric that this alert monitors."
              >
                <select
                  className="rounded-md border border-gray-300 px-3 py-2 text-sm"
                  value={metric || ''}
                  onChange={(e) => handleMetricChange(e.target.value)}
                  disabled={!canEdit}
                >
                  <option value=""></option>
                  {metrics.map((m) => (
                    <option
                      key={m.id}
                      value={m.id}
                      disabled={m.disabled}
                      style={m.heading ? { fontWeight: 'bold' } : undefined}
                    >
                      {m.display}
                    </option>
                  ))}
                </select>
              </GtFormGroup>

              {showTransactionTypeAndName(metric) && (
                <>
                  <GtFormGroup
                    label="Transaction type"
                    required
                    value={config.condition.transactionType || ''}
                    helpText="The transaction type that this alert monitors."
                  >
                    <select
                      className="rounded-md border border-gray-300 px-3 py-2 text-sm"
                      value={config.condition.transactionType || ''}
                      onChange={(e) => updateCondition({ transactionType: e.target.value })}
                      disabled={!canEdit}
                    >
                      {(agentRollup?.transactionTypes || []).map((tt) => (
                        <option key={tt} value={tt}>{tt}</option>
                      ))}
                      {config.condition.transactionType &&
                        !(agentRollup?.transactionTypes || []).includes(config.condition.transactionType) && (
                          <option value={config.condition.transactionType} disabled>
                            {config.condition.transactionType} (not available)
                          </option>
                        )}
                    </select>
                  </GtFormGroup>

                  <GtFormGroup label="Transaction name" helpText="(Optional) The transaction name that this alert monitors.">
                    <Input
                      value={config.condition.transactionName || ''}
                      onChange={(e) => updateCondition({ transactionName: e.target.value })}
                      disabled={!canEdit}
                    />
                  </GtFormGroup>
                </>
              )}

              {metric === 'transaction:x-percentile' && (
                <GtFormGroup
                  label="Percentile"
                  required
                  pattern={PATTERNS.percentage}
                  value={config.condition.percentile ?? ''}
                  helpText="The transaction response time percentile to use for alerting."
                >
                  <div className="flex items-center gap-2">
                    <Input
                      type="number"
                      className="w-24"
                      value={config.condition.percentile ?? ''}
                      onChange={(e) => updateCondition({ percentile: e.target.value === '' ? undefined : Number(e.target.value) })}
                      disabled={!canEdit}
                    />
                    <span className="text-sm text-gray-500">percentile</span>
                  </div>
                </GtFormGroup>
              )}

              {metric === 'error:count' && (
                <GtFormGroup label="Error message filter" helpText="(Optional) Count errors that contain this text. Enclose in forward slashes for regex.">
                  <Input
                    value={config.condition.errorMessageFilter || ''}
                    onChange={(e) => updateCondition({ errorMessageFilter: e.target.value })}
                    disabled={!canEdit}
                  />
                </GtFormGroup>
              )}

              <GtFormGroup
                label="Threshold"
                required
                pattern={PATTERNS.double}
                value={displayThreshold ?? ''}
                helpText={`If the ${phraseForValue(metric)} over the given time period is greater than or equal to this threshold, then alert will be triggered.`}
              >
                <div className="flex items-center gap-2">
                  <Input
                    type="number"
                    className="w-28"
                    value={displayThreshold ?? ''}
                    onChange={(e) => handleDisplayThresholdChange(e.target.value)}
                    disabled={!canEdit}
                  />
                  {isByteUnit ? (
                    <select
                      className="rounded-md border border-gray-300 px-3 py-2 text-sm"
                      value={byteUnit}
                      onChange={(e) => handleByteUnitChange(e.target.value)}
                      disabled={!canEdit}
                    >
                      {BYTE_UNITS.map((u) => (
                        <option key={u.value} value={u.value}>{u.display}</option>
                      ))}
                    </select>
                  ) : unit ? (
                    <span className="text-sm text-gray-500">{unit}</span>
                  ) : null}
                </div>
              </GtFormGroup>

              <GtFormGroup
                label="Lower bound threshold?"
                helpText={`Alert if the ${phraseForValue(metric)} is less than or equal to the threshold, instead of alerting if the ${phraseForValue(metric)} is greater than or equal to the threshold.`}
              >
                <label className="flex items-center gap-2 text-sm">
                  <Checkbox
                    checked={!!config.condition.lowerBoundThreshold}
                    onCheckedChange={(c) => updateCondition({ lowerBoundThreshold: !!c })}
                    disabled={!canEdit}
                  />
                  Lower bound threshold
                </label>
              </GtFormGroup>

              <GtFormGroup
                label="Time period"
                required
                pattern={PATTERNS.integer}
                value={timePeriodMinutes ?? ''}
                helpText={`The time period over which the ${phraseForValue(metric)} is calculated.`}
              >
                <div className="flex items-center gap-2">
                  <Input
                    type="number"
                    className="w-28"
                    value={timePeriodMinutes ?? ''}
                    onChange={(e) => handleTimePeriodMinutesChange(e.target.value)}
                    disabled={!canEdit}
                  />
                  <span className="text-sm text-gray-500">minutes</span>
                </div>
              </GtFormGroup>

              {showMinTransactionCount(metric) && (
                <GtFormGroup
                  label="Minimum transaction count"
                  required
                  pattern={PATTERNS.integer}
                  value={config.condition.minTransactionCount ?? ''}
                  helpText="Time periods with very few transactions have much less meaningful metrics, so this can be used to suppress alerts from being generated unless the time period has a minimum transaction count."
                >
                  <Input
                    type="number"
                    className="w-28"
                    value={config.condition.minTransactionCount ?? ''}
                    onChange={(e) => updateCondition({
                      minTransactionCount: e.target.value === '' ? undefined : Number(e.target.value),
                    })}
                    disabled={!canEdit}
                  />
                </GtFormGroup>
              )}
            </>
          )}

          {/* Heartbeat condition */}
          {config.condition.conditionType === 'heartbeat' && (
            <GtFormGroup
              label="Time period"
              required
              pattern={PATTERNS.integer}
              value={config.condition.timePeriodSeconds ?? ''}
              helpText="The time period after which the alert is triggered if no heartbeat has been received from the agent. Heartbeats are normally received every 5 seconds."
            >
              <div className="flex items-center gap-2">
                <Input
                  type="number"
                  className="w-28"
                  value={config.condition.timePeriodSeconds ?? ''}
                  onChange={(e) => updateCondition({
                    timePeriodSeconds: e.target.value === '' ? undefined : Number(e.target.value),
                  })}
                  disabled={!canEdit}
                />
                <span className="text-sm text-gray-500">seconds</span>
              </div>
            </GtFormGroup>
          )}

          {/* Synthetic monitor condition */}
          {config.condition.conditionType === 'synthetic-monitor' && (
            <>
              <GtFormGroup
                label="Synthetic monitor"
                required
                value={config.condition.syntheticMonitorId || ''}
                helpText="The synthetic monitor that this alert is based on."
              >
                <select
                  className="rounded-md border border-gray-300 px-3 py-2 text-sm"
                  value={config.condition.syntheticMonitorId || ''}
                  onChange={(e) => updateCondition({ syntheticMonitorId: e.target.value })}
                  disabled={!canEdit}
                >
                  {syntheticMonitors.length === 0 ? (
                    <option value="" disabled>(there are no synthetic monitors available)</option>
                  ) : (
                    <option value=""></option>
                  )}
                  {syntheticMonitors.map((sm) => (
                    <option key={sm.id} value={sm.id}>{sm.display}</option>
                  ))}
                </select>
              </GtFormGroup>

              <GtFormGroup
                label="Duration threshold"
                required
                pattern={PATTERNS.integer}
                value={config.condition.thresholdMillis ?? ''}
                helpText="If the synthetic monitor duration meets this, it is considered a failure."
              >
                <div className="flex items-center gap-2">
                  <Input
                    type="number"
                    className="w-28"
                    value={config.condition.thresholdMillis ?? ''}
                    onChange={(e) => updateCondition({
                      thresholdMillis: e.target.value === '' ? undefined : Number(e.target.value),
                    })}
                    disabled={!canEdit}
                  />
                  <span className="text-sm text-gray-500">milliseconds</span>
                </div>
              </GtFormGroup>

              <GtFormGroup
                label="Consecutive count"
                required
                pattern={PATTERNS.integer}
                value={config.condition.consecutiveCount ?? ''}
                helpText="Number of consecutive failures before alert is triggered."
              >
                <Input
                  type="number"
                  className="w-28"
                  value={config.condition.consecutiveCount ?? ''}
                  onChange={(e) => updateCondition({
                    consecutiveCount: e.target.value === '' ? undefined : Number(e.target.value),
                  })}
                  disabled={!canEdit}
                />
              </GtFormGroup>
            </>
          )}
        </fieldset>

        {/* Severity */}
        <fieldset className="mb-6">
          <legend className="text-sm font-semibold text-gray-700 mb-3">Severity</legend>
          <GtFormGroup label="Severity" required value={config.severity || ''}>
            <select
              className="rounded-md border border-gray-300 px-3 py-2 text-sm"
              value={config.severity || ''}
              onChange={(e) => updateConfig((c) => ({ ...c, severity: e.target.value }))}
              disabled={!canEdit}
            >
              {!config.severity && <option value=""></option>}
              <option value="critical">Critical</option>
              <option value="high">High</option>
              <option value="medium">Medium</option>
              <option value="low">Low</option>
            </select>
          </GtFormGroup>
        </fieldset>

        {/* Email notification */}
        <fieldset className="mb-6">
          <legend className="text-sm font-semibold text-gray-700 mb-3">Email notification</legend>
          <GtFormGroup label="Email addresses" helpText="Comma separated list of email addresses.">
            <textarea
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
              rows={2}
              value={emailAddressesStr}
              onChange={(e) => handleEmailAddressesChange(e.target.value)}
              disabled={!canEdit}
            />
          </GtFormGroup>
        </fieldset>

        {/* PagerDuty notification */}
        {pagerDutyIntegrationKeys.length > 0 && (
          <fieldset className="mb-6">
            <legend className="text-sm font-semibold text-gray-700 mb-3">PagerDuty notification</legend>
            <GtFormGroup label="Integration key">
              <select
                className="rounded-md border border-gray-300 px-3 py-2 text-sm"
                value={config.pagerDutyNotification?.pagerDutyIntegrationKey || ''}
                onChange={(e) => updateConfig((c) => ({
                  ...c,
                  pagerDutyNotification: { ...c.pagerDutyNotification!, pagerDutyIntegrationKey: e.target.value },
                }))}
                disabled={!canEdit}
              >
                <option value=""></option>
                {pagerDutyIntegrationKeys.map((k) => (
                  <option key={k.key} value={k.key}>
                    {k.display} ({k.key})
                  </option>
                ))}
                {config.pagerDutyNotification?.pagerDutyIntegrationKey &&
                  !pagerDutyIntegrationKeys.find((k) => k.key === config.pagerDutyNotification!.pagerDutyIntegrationKey) && (
                    <option value={config.pagerDutyNotification.pagerDutyIntegrationKey} disabled>
                      {config.pagerDutyNotification.pagerDutyIntegrationKey} (not available)
                    </option>
                  )}
              </select>
            </GtFormGroup>
          </fieldset>
        )}

        {/* Slack notification */}
        {slackWebhooks.length > 0 && (
          <fieldset className="mb-6">
            <legend className="text-sm font-semibold text-gray-700 mb-3">Slack notification</legend>
            <GtFormGroup label="Slack webhook">
              <select
                className="rounded-md border border-gray-300 px-3 py-2 text-sm"
                value={config.slackNotification?.slackWebhookId || ''}
                onChange={(e) => {
                  const newId = e.target.value
                  updateConfig((c) => ({
                    ...c,
                    slackNotification: { ...c.slackNotification!, slackWebhookId: newId },
                  }))
                  if (!newId) setSlackChannelsStr('')
                }}
                disabled={!canEdit}
              >
                <option value=""></option>
                {slackWebhooks.map((w) => (
                  <option key={w.id} value={w.id}>{w.display}</option>
                ))}
                {config.slackNotification?.slackWebhookId &&
                  !slackWebhooks.find((w) => w.id === config.slackNotification!.slackWebhookId) && (
                    <option value={config.slackNotification.slackWebhookId} disabled>
                      {config.slackNotification.slackWebhookId} (not available)
                    </option>
                  )}
              </select>
            </GtFormGroup>

            {config.slackNotification?.slackWebhookId && (
              <GtFormGroup
                label="Slack channels"
                required
                value={slackChannelsStr}
                helpText="Comma separated list of channels, e.g. #alerts"
              >
                <Input
                  value={slackChannelsStr}
                  onChange={(e) => handleSlackChannelsChange(e.target.value)}
                  disabled={!canEdit}
                />
              </GtFormGroup>
            )}
          </fieldset>
        )}

        {canEdit && (
          <GtButtonGroup
            onSave={handleSave}
            hasChanges={!!hasChanges}
            saveLabel={isNew ? 'Add' : 'Save changes'}
            onDelete={!isNew ? handleDelete : undefined}
            showDelete={!isNew}
            deleteConfirmHeader="Delete alert"
            deleteConfirmMessage="Are you sure you want to delete this alert?"
          />
        )}
      </div>
    </div>
  )
}
