import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet, apiPost, ApiError } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Checkbox } from '../../components/ui/checkbox'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface MBeanAttribute {
  name: string
  counter: boolean
}

interface GaugeConfig {
  display?: string
  displayPath?: string[]
  mbeanObjectName: string
  mbeanAttributes: MBeanAttribute[]
  version?: string
}

interface GaugeData {
  config: GaugeConfig
  agentNotConnected?: boolean
  noMatchFoundForPattern?: boolean
  noMatchFoundForNonPattern?: boolean
  mbeanAvailableAttributeNames?: string[]
}

interface AttributeState {
  name: string
  counter: boolean
  checked: boolean
  available: boolean
}

export function ConfigGaugePage() {
  const { agentId, agentRollup, agentQueryString } = useAgent()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const version = searchParams.get('v')
  const isNew = !version

  const [config, setConfig] = useState<GaugeConfig>({ mbeanObjectName: '', mbeanAttributes: [] })
  const [originalConfig, setOriginalConfig] = useState<GaugeConfig>({ mbeanObjectName: '', mbeanAttributes: [] })
  const [allAttributes, setAllAttributes] = useState<AttributeState[]>([])
  const [mbeanSuggestions, setMbeanSuggestions] = useState<string[]>([])
  const [showSuggestions, setShowSuggestions] = useState(false)
  const selectedMBeanRef = useRef('')
  const [agentNotConnected, setAgentNotConnected] = useState(false)
  const [noMatchForPattern, setNoMatchForPattern] = useState(false)
  const [noMatchForNonPattern, setNoMatchForNonPattern] = useState(false)
  const [duplicateMBean, setDuplicateMBean] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.gauges ?? false

  function buildAttributeStates(
    configAttrs: MBeanAttribute[],
    availableNames?: string[]
  ): AttributeState[] {
    const result: AttributeState[] = []
    const availableSet = new Set(availableNames || [])
    // Available attributes first
    for (const name of availableNames || []) {
      const existing = configAttrs.find((a) => a.name === name)
      result.push({
        name,
        counter: existing?.counter ?? false,
        checked: !!existing,
        available: true,
      })
    }
    // Then config attributes not in available list
    for (const attr of configAttrs) {
      if (!availableSet.has(attr.name)) {
        result.push({
          name: attr.name,
          counter: attr.counter,
          checked: true,
          available: false,
        })
      }
    }
    return result
  }

  const load = useCallback(async () => {
    if (agentId === null) return
    try {
      if (isNew) {
        const connected = await apiGet<boolean>('/backend/config/new-gauge-check-agent-connected', {
          'agent-id': agentId,
        })
        setAgentNotConnected(!connected)
        selectedMBeanRef.current = ''
        setConfig({ mbeanObjectName: '', mbeanAttributes: [] })
        setOriginalConfig({ mbeanObjectName: '', mbeanAttributes: [] })
      } else if (version) {
        const resp = await apiGet<GaugeData>('/backend/config/gauges', {
          'agent-id': agentId,
          version,
        })
        selectedMBeanRef.current = resp.config.mbeanObjectName
        setConfig({ ...resp.config })
        setOriginalConfig({ ...resp.config })
        setAgentNotConnected(!!resp.agentNotConnected)
        setNoMatchForPattern(!!resp.noMatchFoundForPattern)
        setNoMatchForNonPattern(!!resp.noMatchFoundForNonPattern)
        setAllAttributes(
          buildAttributeStates(resp.config.mbeanAttributes, resp.mbeanAvailableAttributeNames)
        )
      }
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentId, isNew, version])

  useEffect(() => { load() }, [load])

  async function fetchMatchingMBeans(partial: string) {
    if (agentNotConnected || agentId === null) {
      setMbeanSuggestions([])
      return
    }
    if (partial.includes('*')) {
      setMbeanSuggestions([partial])
      return
    }
    try {
      const limit = 100
      const list = await apiGet<string[]>('/backend/config/matching-mbean-objects', {
        'agent-id': agentId,
        'partial-object-name': partial,
        limit: limit + 1,
      })
      setMbeanSuggestions(list.slice(0, limit))
    } catch {
      setMbeanSuggestions([])
    }
  }

  async function fetchAttributes(mbeanObjectName: string) {
    if (agentId === null) return
    if (!mbeanObjectName) {
      selectedMBeanRef.current = ''
      setNoMatchForPattern(false)
      setNoMatchForNonPattern(false)
      setDuplicateMBean(false)
      setAllAttributes([])
      return
    }
    if (mbeanObjectName === selectedMBeanRef.current) return
    selectedMBeanRef.current = mbeanObjectName
    try {
      const resp = await apiGet<{
        noMatchFoundForPattern?: boolean
        noMatchFoundForNonPattern?: boolean
        duplicateMBean?: boolean
        mbeanAttributes?: string[]
      }>('/backend/config/mbean-attributes', {
        'agent-id': agentId,
        'object-name': mbeanObjectName,
        'gauge-version': config.version || '',
      })
      setNoMatchForPattern(!!resp.noMatchFoundForPattern)
      setNoMatchForNonPattern(!!resp.noMatchFoundForNonPattern)
      setDuplicateMBean(!!resp.duplicateMBean)
      if (resp.mbeanAttributes) {
        setAllAttributes(
          buildAttributeStates(config.mbeanAttributes, resp.mbeanAttributes)
        )
      }
    } catch {
      // ignore
    }
  }

  function toggleAttribute(index: number, checked: boolean) {
    const updated = [...allAttributes]
    updated[index] = { ...updated[index], checked }
    setAllAttributes(updated)
    // Sync back to config
    const attrs = updated
      .filter((a) => a.checked)
      .map((a) => ({ name: a.name, counter: a.counter }))
      .sort((a, b) => a.name.localeCompare(b.name))
    setConfig({ ...config, mbeanAttributes: attrs })
  }

  function toggleCounter(index: number, counter: boolean) {
    const updated = [...allAttributes]
    updated[index] = { ...updated[index], counter }
    setAllAttributes(updated)
    const attrs = updated
      .filter((a) => a.checked)
      .map((a) => ({ name: a.name, counter: a.counter }))
      .sort((a, b) => a.name.localeCompare(b.name))
    setConfig({ ...config, mbeanAttributes: attrs })
  }

  const hasChanges = JSON.stringify(config) !== JSON.stringify(originalConfig)

  async function handleSave() {
    if (agentId === null) return
    try {
      const endpoint = isNew
        ? '/backend/config/gauges/add'
        : '/backend/config/gauges/update'
      const resp = await apiPost<GaugeData>(
        endpoint + '?agent-id=' + encodeURIComponent(agentId),
        config
      )
      if (isNew) {
        navigate(
          `/modern/config/gauge?agent-id=${encodeURIComponent(agentId)}&v=${encodeURIComponent(resp.config.version!)}`,
          { replace: true }
        )
      }
      setConfig({ ...resp.config })
      setOriginalConfig({ ...resp.config })
      setAllAttributes(
        buildAttributeStates(resp.config.mbeanAttributes, resp.mbeanAvailableAttributeNames)
      )
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setDuplicateMBean(true)
        throw new Error('A gauge already exists for this MBean')
      }
      throw e
    }
  }

  async function handleDelete() {
    if (agentId === null) return
    await apiPost(
      '/backend/config/gauges/remove?agent-id=' + encodeURIComponent(agentId),
      { version: config.version }
    )
    navigate(`/modern/config/gauge-list?agent-id=${encodeURIComponent(agentId)}`, { replace: true })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  return (
    <div>
      <div className="mb-3">
        <Link
          to={'/modern/config/gauge-list' + agentQueryString()}
          className="text-sm text-blue-600 hover:underline"
        >
          Return to list
        </Link>
      </div>
      <SettingsPage title={`Gauges — ${isNew ? '<New>' : (config.display || config.mbeanObjectName)}`}>
        {agentNotConnected && (
          <div className="mb-4 rounded-md border border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-800">
            Limited functionality since agent is not currently connected
          </div>
        )}

        <GtFormGroup
          label="MBean object name"
          required
          value={config.mbeanObjectName}
        >
          <div className="relative">
            <Input
              value={config.mbeanObjectName}
              onChange={(e) => {
                const val = e.target.value
                setConfig({ ...config, mbeanObjectName: val })
                setShowSuggestions(true)
                fetchMatchingMBeans(val)
              }}
              onFocus={() => {
                if (config.mbeanObjectName || mbeanSuggestions.length > 0) {
                  setShowSuggestions(true)
                } else {
                  setShowSuggestions(true)
                  fetchMatchingMBeans('')
                }
              }}
              onBlur={() => {
                setShowSuggestions(false)
                fetchAttributes(config.mbeanObjectName)
              }}
              disabled={!canEdit}
              placeholder="e.g. java.lang:type=Memory"
            />
            {showSuggestions && mbeanSuggestions.length > 0 && canEdit && (
              <ul className="absolute z-20 mt-1 max-h-56 w-full overflow-auto rounded-md border border-gray-200 bg-white py-1 text-xs shadow-lg">
                {mbeanSuggestions.map((item) => (
                  <li key={item}>
                    <button
                      type="button"
                      className="w-full px-3 py-1.5 text-left font-mono hover:bg-blue-50"
                      onMouseDown={(e) => {
                        e.preventDefault()
                        setConfig({ ...config, mbeanObjectName: item })
                        setShowSuggestions(false)
                        fetchAttributes(item)
                      }}
                    >
                      {item}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          {noMatchForPattern && (
            <p className="mt-1 text-xs text-red-600">No matching MBean found</p>
          )}
          {noMatchForNonPattern && (
            <p className="mt-1 text-xs text-red-600">MBean not found</p>
          )}
          {duplicateMBean && (
            <p className="mt-1 text-xs text-red-600">There is already a gauge for this MBean</p>
          )}
          <p className="mt-1.5 text-xs text-gray-500">
            If the object name includes at least one occurrence of the wildcard characters asterisk
            (*) or question mark (?), then the object name is a pattern. The asterisk matches any
            sequence of zero or more characters, while the question mark matches any single
            character. (see{' '}
            <a
              href="https://docs.oracle.com/javase/8/docs/api/javax/management/ObjectName.html"
              target="_blank"
              rel="noopener noreferrer"
              className="text-blue-600 hover:underline"
            >
              https://docs.oracle.com/javase/8/docs/api/javax/management/ObjectName.html
            </a>
            )
          </p>
        </GtFormGroup>

        <GtFormGroup
          label="MBean attributes"
          helpText={
            config.mbeanAttributes.length > 0
              ? 'Marking an MBean attribute as a counter indicates that it is a monotonically increasing value (e.g. the total garbage collection time since JVM startup). When an mbean attribute is marked as a counter, the deltas are captured and displayed instead of the raw values (e.g. the total garbage collection time since the last capture). Also, when rolling up mbean attributes that are marked as counters, the (delta) values are weighted by the time between captures instead of a straight average.'
              : undefined
          }
        >
          {!config.mbeanObjectName ? (
            <div className="text-sm italic text-gray-500">(select MBean object name first)</div>
          ) : allAttributes.length === 0 && !noMatchForPattern && !noMatchForNonPattern && selectedMBeanRef.current ? (
            <div className="text-sm text-red-600">There are no number-valued attributes for this MBean</div>
          ) : (
            <div className="space-y-2">
              {allAttributes.map((attr, i) => (
                <div key={attr.name} className="flex items-center gap-4">
                  <label className="flex items-center gap-2 text-sm min-w-0 flex-1">
                    <Checkbox
                      checked={attr.checked}
                      onCheckedChange={(c) => toggleAttribute(i, !!c)}
                      disabled={!canEdit}
                    />
                    <span className={attr.available ? '' : 'text-gray-400'}>
                      {attr.name.replace('/', ' / ')}
                      {!attr.available && !noMatchForPattern && !noMatchForNonPattern && (
                        <span className="ml-2 text-red-600">Attribute not found</span>
                      )}
                    </span>
                  </label>
                  {attr.checked && (
                    <label className="flex items-center gap-1 text-xs text-gray-500 shrink-0">
                      <Checkbox
                        checked={attr.counter}
                        onCheckedChange={(c) => toggleCounter(i, !!c)}
                        disabled={!canEdit}
                      />
                      Counter?
                    </label>
                  )}
                </div>
              ))}
            </div>
          )}
        </GtFormGroup>

        {canEdit && (
          <GtButtonGroup
            onSave={handleSave}
            hasChanges={hasChanges}
            validate={() => {
              if (!config.mbeanAttributes.length) {
                return 'At least one MBean attribute must be selected'
              }
              if (noMatchForPattern) {
                return 'Cannot save because MBean is unmatched'
              }
              if (noMatchForNonPattern) {
                return 'Cannot save because MBean is unavailable'
              }
              if (duplicateMBean) {
                return 'Cannot save because MBean is a duplicate of another gauge'
              }
              return true
            }}
            saveLabel={isNew ? 'Add' : 'Save changes'}
            onDelete={!isNew && config.version ? handleDelete : undefined}
            showDelete={!isNew && !!config.version}
            deleteConfirmHeader="Delete gauge"
            deleteConfirmMessage="Are you sure you want to delete this gauge?"
          />
        )}
      </SettingsPage>
    </div>
  )
}
