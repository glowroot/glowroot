import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { PATTERNS } from '../../lib/validation'
import { apiGet, apiPost } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Checkbox } from '../../components/ui/checkbox'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface PluginProperty {
  name: string
  type: 'boolean' | 'double' | 'string' | 'list'
  value: unknown
  label?: string
  checkboxLabel?: string
  description?: string
}

interface PluginConfig {
  name: string
  properties: PluginProperty[]
  version: string
}

export function ConfigPluginPage() {
  const { agentId, agentRollup, agentQueryString } = useAgent()
  const [searchParams] = useSearchParams()
  const pluginId = searchParams.get('plugin-id')

  const [config, setConfig] = useState<PluginConfig | null>(null)
  const [originalConfig, setOriginalConfig] = useState<PluginConfig | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const canEdit = agentRollup?.permissions?.config?.edit?.plugins ?? false

  function onNewData(data: PluginConfig) {
    // For LIST properties, ensure there's an empty trailing item for adding
    const props = data.properties.map((p) => {
      if (p.type.toLowerCase() === 'list') {
        const list = [...(p.value as string[])]
        if (list.length === 0 || list[list.length - 1].trim() !== '') {
          list.push('')
        }
        return { ...p, value: list }
      }
      return { ...p }
    })
    setConfig({ ...data, properties: props })
    setOriginalConfig({ ...data, properties: data.properties.map((p) => ({ ...p })) })
  }

  const load = useCallback(async () => {
    if (agentId === null || !pluginId) return
    try {
      const resp = await apiGet<PluginConfig>('/backend/config/plugins', {
        'agent-id': agentId,
        'plugin-id': pluginId,
      })
      onNewData(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [agentId, pluginId])

  useEffect(() => { load() }, [load])

  function cleanProperties(): PluginProperty[] {
    if (!config) return []
    return config.properties.map((p) => {
      if (p.type.toLowerCase() === 'list') {
        const list = (p.value as string[]).map((s) => s.trim()).filter(Boolean)
        return { ...p, value: list }
      }
      return { ...p }
    })
  }

  function hasChanges(): boolean {
    if (!config || !originalConfig) return false
    const cleaned = cleanProperties()
    return JSON.stringify(cleaned) !== JSON.stringify(originalConfig.properties)
  }

  async function handleSave() {
    if (agentId === null || !pluginId || !config) return
    const postData = {
      pluginId,
      properties: cleanProperties(),
      version: config.version,
    }
    const resp = await apiPost<PluginConfig & { firstInvalidRegularExpression?: string }>(
      '/backend/config/plugins?agent-id=' + encodeURIComponent(agentId),
      postData
    )
    if (resp.firstInvalidRegularExpression) {
      throw new Error(`First invalid regular expression: ${resp.firstInvalidRegularExpression}`)
    }
    onNewData(resp)
  }

  function updateProperty(index: number, value: unknown) {
    if (!config) return
    const updated = [...config.properties]
    updated[index] = { ...updated[index], value }
    // For LIST: ensure trailing empty item
    if (updated[index].type.toLowerCase() === 'list') {
      const list = value as string[]
      if (list.length === 0 || list[list.length - 1].trim() !== '') {
        updated[index] = { ...updated[index], value: [...list, ''] }
      }
    }
    setConfig({ ...config, properties: updated })
  }

  function updateListItem(propIndex: number, itemIndex: number, value: string) {
    if (!config) return
    const list = [...(config.properties[propIndex].value as string[])]
    list[itemIndex] = value
    updateProperty(propIndex, list)
  }

  function removeListItem(propIndex: number, itemIndex: number) {
    if (!config) return
    const list = [...(config.properties[propIndex].value as string[])]
    list.splice(itemIndex, 1)
    updateProperty(propIndex, list)
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!config) return null

  return (
    <div>
      <div className="mb-3">
        <Link
          to={'/modern/config/plugin-list' + agentQueryString()}
          className="text-sm text-blue-600 hover:underline"
        >
          Return to list
        </Link>
      </div>
      <SettingsPage title={`Plugins — ${config.name}`}>
        {config.properties.map((prop, i) => {
          const propType = prop.type.toLowerCase()
          if (propType === 'boolean') {
            return (
              <GtFormGroup key={prop.name} label={prop.label || prop.name}>
                <label className="flex items-center gap-2 text-sm">
                  <Checkbox
                    checked={prop.value as boolean}
                    onCheckedChange={(c) => updateProperty(i, !!c)}
                    disabled={!canEdit}
                  />
                  {prop.checkboxLabel || ''}
                </label>
                {prop.description && (
                  <p className="mt-1 text-xs text-gray-500">{prop.description}</p>
                )}
              </GtFormGroup>
            )
          }
          if (propType === 'double' || propType === 'string') {
            return (
              <GtFormGroup
                key={prop.name}
                label={prop.label || prop.name}
                pattern={propType === 'double' ? PATTERNS.double : undefined}
                value={prop.value as string | number ?? ''}
              >
                <Input
                  type={propType === 'double' ? 'number' : 'text'}
                  value={prop.value as string | number ?? ''}
                  onChange={(e) => {
                    const val = propType === 'double'
                      ? (e.target.value === '' ? null : parseFloat(e.target.value))
                      : e.target.value
                    updateProperty(i, val)
                  }}
                  disabled={!canEdit}
                />
                {prop.description && (
                  <p className="mt-1 text-xs text-gray-500">{prop.description}</p>
                )}
              </GtFormGroup>
            )
          }
          if (propType === 'list') {
            const list = prop.value as string[]
            return (
              <GtFormGroup key={prop.name} label={prop.label || prop.name}>
                <div className="space-y-1">
                  {list.map((item, j) => (
                    <div key={j} className="flex items-center gap-2">
                      <Input
                        value={item}
                        onChange={(e) => updateListItem(i, j, e.target.value)}
                        disabled={!canEdit}
                      />
                      {j < list.length - 1 && canEdit && (
                        <button
                          type="button"
                          onClick={() => removeListItem(i, j)}
                          className="text-gray-400 hover:text-red-500 text-sm"
                        >
                          &times;
                        </button>
                      )}
                    </div>
                  ))}
                </div>
                {prop.description && (
                  <p className="mt-1 text-xs text-gray-500">{prop.description}</p>
                )}
              </GtFormGroup>
            )
          }
          return null
        })}
        {canEdit && (
          <GtButtonGroup onSave={handleSave} hasChanges={hasChanges()} />
        )}
      </SettingsPage>
    </div>
  )
}
