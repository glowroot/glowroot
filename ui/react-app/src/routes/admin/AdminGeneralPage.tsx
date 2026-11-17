import { useCallback, useEffect, useState } from 'react'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface GeneralConfig {
  agentDisplayName?: string
  centralDisplayName?: string
  version?: string
}

interface GeneralData {
  config: GeneralConfig
  defaultAgentDisplayName?: string
}

export function AdminGeneralPage() {
  const { layout } = useLayout()
  const [data, setData] = useState<GeneralData | null>(null)
  const [config, setConfig] = useState<GeneralConfig>({})
  const [originalConfig, setOriginalConfig] = useState<GeneralConfig>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    try {
      if (layout.central) {
        // Central mode: response is the config directly (not wrapped in {config: ...})
        const resp = await apiGet<GeneralConfig>('/backend/admin/general')
        setData({ config: resp })
        setConfig({ ...resp })
        setOriginalConfig({ ...resp })
      } else {
        // Embedded mode: response is {config: ..., defaultAgentDisplayName: ...}
        const resp = await apiGet<GeneralData>('/backend/admin/general')
        setData(resp)
        setConfig({ ...resp.config })
        setOriginalConfig({ ...resp.config })
      }
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [layout.central])

  useEffect(() => { load() }, [load])

  const hasChanges = JSON.stringify(config) !== JSON.stringify(originalConfig)

  async function handleSave() {
    if (layout.central) {
      const resp = await apiPost<GeneralConfig>('/backend/admin/general', config)
      setData({ config: resp })
      setConfig({ ...resp })
      setOriginalConfig({ ...resp })
    } else {
      const resp = await apiPost<GeneralData>('/backend/admin/general', config)
      setData(resp)
      setConfig({ ...resp.config })
      setOriginalConfig({ ...resp.config })
    }
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!data) return null

  return (
    <SettingsPage title="General">
      {!layout.central && (
        <GtFormGroup
          label="Agent display name"
          helpText="Display name for this agent. Glowroot displays this name prominently in the UI"
        >
          <Input
            value={config.agentDisplayName ?? ''}
            onChange={(e) => setConfig({ ...config, agentDisplayName: e.target.value })}
            placeholder={data.defaultAgentDisplayName}
            disabled={!layout.adminEdit}
          />
        </GtFormGroup>
      )}
      {layout.central && (
        <GtFormGroup
          label="Glowroot central display name"
          helpText="Display name for this Glowroot central installation"
        >
          <Input
            value={config.centralDisplayName ?? ''}
            onChange={(e) => setConfig({ ...config, centralDisplayName: e.target.value })}
            disabled={!layout.adminEdit}
          />
        </GtFormGroup>
      )}
      {layout.adminEdit && (
        <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
      )}
    </SettingsPage>
  )
}
