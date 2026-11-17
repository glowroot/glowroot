import { useCallback, useEffect, useState } from 'react'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet, apiPost } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'
import { PATTERNS } from '../../lib/validation'

interface UiDefaultsConfig {
  defaultTransactionType: string
  defaultPercentiles: number[]
  defaultGaugeNames: string[]
  version: string
}

interface Gauge {
  name: string
  display: string
  disabled?: boolean
}

interface UiDefaultsData {
  config: UiDefaultsConfig
  allTransactionTypes: string[]
  allGauges: Gauge[]
}

export function ConfigUiDefaultsPage() {
  const { agentRollupId, agentRollup } = useAgent()
  const [data, setData] = useState<UiDefaultsData | null>(null)
  const [config, setConfig] = useState<UiDefaultsConfig | null>(null)
  const [originalConfig, setOriginalConfig] = useState<UiDefaultsConfig | null>(null)
  const [percentilesStr, setPercentilesStr] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.uiDefaults ?? false

  function onNewData(resp: UiDefaultsData) {
    // Add any missing gauge names that the config references
    const allGaugeNames = resp.allGauges.map((g) => g.name)
    const allGauges = [...resp.allGauges]
    for (const name of resp.config.defaultGaugeNames) {
      if (!allGaugeNames.includes(name)) {
        allGauges.push({ name, display: name + ' (not available)', disabled: true })
      }
    }
    // Missing defaultTransactionType is rendered as a disabled option below
    const allTransactionTypes = [...resp.allTransactionTypes]
    setData({ ...resp, allGauges, allTransactionTypes })
    setConfig({ ...resp.config, defaultGaugeNames: [...resp.config.defaultGaugeNames] })
    setOriginalConfig({ ...resp.config, defaultGaugeNames: [...resp.config.defaultGaugeNames] })
    setPercentilesStr(resp.config.defaultPercentiles.join(', '))
  }

  const load = useCallback(async () => {
    if (agentRollupId === null) return
    try {
      const resp = await apiGet<UiDefaultsData>('/backend/config/ui-defaults', {
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

  function buildConfig(): UiDefaultsConfig {
    const percentiles = percentilesStr
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .map(Number)
      .filter((n) => !isNaN(n))
    return { ...config!, defaultPercentiles: percentiles }
  }

  const hasChanges = config && originalConfig
    ? JSON.stringify(buildConfig()) !== JSON.stringify(originalConfig)
    : false

  async function handleSave() {
    if (agentRollupId === null) return
    const resp = await apiPost<UiDefaultsData>(
      '/backend/config/ui-defaults?agent-rollup-id=' + encodeURIComponent(agentRollupId),
      buildConfig()
    )
    onNewData(resp)
  }

  function toggleGauge(name: string, checked: boolean) {
    if (!config) return
    const names = checked
      ? [...config.defaultGaugeNames, name]
      : config.defaultGaugeNames.filter((n) => n !== name)
    setConfig({ ...config, defaultGaugeNames: names })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!config || !data) return null

  return (
    <SettingsPage title="UI Defaults">
<GtFormGroup
        label="Default transaction type"
        required
        value={config.defaultTransactionType}
        helpText="The default transaction type displayed on the transactions and errors page."
      >
        <select
          value={config.defaultTransactionType}
          onChange={(e) => setConfig({ ...config, defaultTransactionType: e.target.value })}
          disabled={!canEdit}
          className="flex h-9 w-full rounded-md border border-gray-300 bg-white px-3 py-1 text-sm"
        >
          {data.allTransactionTypes.map((tt) => (
            <option key={tt} value={tt}>{tt}</option>
          ))}
          {data.config.defaultTransactionType &&
            !data.allTransactionTypes.includes(data.config.defaultTransactionType) && (
              <option value={data.config.defaultTransactionType} disabled>
                {data.config.defaultTransactionType} (not available)
              </option>
            )}
        </select>
      </GtFormGroup>
      <GtFormGroup
        label="Default percentiles"
        required
        pattern={PATTERNS.percentageList}
        value={percentilesStr}
      >
        <Input
          value={percentilesStr}
          onChange={(e) => setPercentilesStr(e.target.value)}
          disabled={!canEdit}
        />
        <p className="mt-1.5 text-xs text-gray-500">
          The default percentiles displayed on the transaction percentiles tab.{' '}
          <a
            href="http://hdrhistogram.github.io/HdrHistogram/"
            target="_blank"
            rel="noopener noreferrer"
            className="text-blue-600 hover:underline"
          >
            HdrHistogram
          </a>{' '}
          is used to bucket and store the full range of values so that any percentile value can
          be reported with at most 1% error, e.g. if the 99.9<sup>th</sup> percentile is reported
          as 1400 milliseconds, then that would be at most 14 milliseconds from the true 99.9
          <sup>th</sup> percentile value.
        </p>
      </GtFormGroup>
      <GtFormGroup
        label="Default gauges"
        helpText="The default gauges displayed on the JVM gauges page."
      >
        <div className="max-h-52 overflow-y-auto rounded border p-2 space-y-1">
          {data.allGauges.map((gauge) => (
            <label key={gauge.name} className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={config.defaultGaugeNames.includes(gauge.name)}
                onChange={(e) => toggleGauge(gauge.name, e.target.checked)}
                disabled={!canEdit}
              />
              <span className={gauge.disabled ? 'text-gray-400' : ''}>{gauge.display}</span>
            </label>
          ))}
        </div>
      </GtFormGroup>
      {canEdit && (
        <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
      )}
    </SettingsPage>
  )
}
