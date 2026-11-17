import { useCallback, useEffect, useState } from 'react'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface HealthchecksIoConfig {
  pingUrl?: string
  version?: string
}

export function AdminHealthchecksIoPage() {
  const { layout } = useLayout()
  const [config, setConfig] = useState<HealthchecksIoConfig>({})
  const [originalConfig, setOriginalConfig] = useState<HealthchecksIoConfig>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    if (layout.central) {
      setLoading(false)
      return
    }
    try {
      const resp = await apiGet<HealthchecksIoConfig>('/backend/admin/healthchecks-io')
      setConfig({ ...resp })
      setOriginalConfig({ ...resp })
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [layout.central])

  useEffect(() => { load() }, [load])

  const hasChanges = JSON.stringify(config) !== JSON.stringify(originalConfig)

  async function handleSave() {
    const resp = await apiPost<HealthchecksIoConfig>('/backend/admin/healthchecks-io', config)
    setConfig({ ...resp })
    setOriginalConfig({ ...resp })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  if (layout.central) {
    return (
      <SettingsPage
        title="Healthchecks.io"
        back={{ to: '/modern/admin/integration-list', label: 'Integrations' }}
      >
          <div
            role="alert"
            className="rounded-md border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-800"
          >
            Use glowroot agent (with embedded database and UI, not pointing to central) to monitor glowroot central itself.
          </div>
      </SettingsPage>
    )
  }

  return (
    <SettingsPage
      title="Healthchecks.io"
      back={{ to: '/modern/admin/integration-list', label: 'Integrations' }}
    >
        <GtFormGroup label="Ping URL">
          <Input
            value={config.pingUrl ?? ''}
            onChange={(e) => setConfig({ ...config, pingUrl: e.target.value })}
            disabled={!layout.adminEdit}
          />
        </GtFormGroup>
        {layout.adminEdit && (
          <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
        )}
    </SettingsPage>
  )
}
