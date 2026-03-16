import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Plus, Trash2 } from 'lucide-react'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface IntegrationKey {
  key: string
  display: string
  new?: boolean
}

interface PagerDutyConfig {
  integrationKeys?: IntegrationKey[]
  version?: string
}

export function AdminPagerDutyPage() {
  const { layout } = useLayout()
  const [config, setConfig] = useState<PagerDutyConfig>({})
  const [originalConfig, setOriginalConfig] = useState<PagerDutyConfig>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  function onNewData(data: PagerDutyConfig) {
    setConfig({ ...data, integrationKeys: data.integrationKeys?.map(k => ({ ...k })) ?? [] })
    setOriginalConfig({ ...data, integrationKeys: data.integrationKeys?.map(k => ({ ...k })) ?? [] })
  }

  const load = useCallback(async () => {
    try {
      const resp = await apiGet<PagerDutyConfig>('/backend/admin/pager-duty')
      onNewData(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const hasChanges = JSON.stringify(config) !== JSON.stringify(originalConfig)

  async function handleSave() {
    // Strip the `new` flag before posting
    const postConfig = {
      ...config,
      integrationKeys: config.integrationKeys?.map(({ new: _, ...rest }) => rest),
    }
    const resp = await apiPost<
      PagerDutyConfig & {
        duplicateIntegrationKey?: boolean
        duplicateIntegrationKeyDisplay?: boolean
      }
    >('/backend/admin/pager-duty', postConfig)
    if (resp.duplicateIntegrationKey) {
      throw new Error('Duplicate integration key found')
    }
    if (resp.duplicateIntegrationKeyDisplay) {
      throw new Error('Duplicate integration key display found')
    }
    onNewData(resp)
  }

  function addKey() {
    setConfig({
      ...config,
      integrationKeys: [...(config.integrationKeys ?? []), { key: '', display: '', new: true }],
    })
  }

  function removeKey(index: number) {
    const updated = [...(config.integrationKeys ?? [])]
    updated.splice(index, 1)
    setConfig({ ...config, integrationKeys: updated })
  }

  function updateKey(index: number, field: keyof IntegrationKey, value: string) {
    const updated = [...(config.integrationKeys ?? [])]
    updated[index] = { ...updated[index], [field]: value }
    setConfig({ ...config, integrationKeys: updated })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  return (
    <div>
      <div className="mb-3">
        <Link to="/modern/admin/integration-list" className="text-sm text-blue-600 hover:underline">
          Return to list
        </Link>
      </div>
      <div className="rounded-lg border bg-white p-6">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">Integrations &mdash; PagerDuty</h2>
        <h3 className="text-sm font-semibold text-gray-900 mb-4">Integration keys</h3>
        {(config.integrationKeys ?? []).map((ik, i) => (
          <div key={i} className="mb-4 rounded border p-4 bg-gray-50">
            <GtFormGroup label="Integration key" required value={ik.key}>
              <Input
                value={ik.key}
                onChange={(e) => updateKey(i, 'key', e.target.value)}
                disabled={!layout.adminEdit || !ik.new}
              />
            </GtFormGroup>
            <GtFormGroup label="Display" required value={ik.display}>
              <Input
                value={ik.display}
                onChange={(e) => updateKey(i, 'display', e.target.value)}
                disabled={!layout.adminEdit}
              />
            </GtFormGroup>
            {layout.adminEdit && (
              <div className="flex justify-end">
                <Button variant="ghost" size="sm" onClick={() => removeKey(i)}>
                  <Trash2 className="h-4 w-4" /> Remove
                </Button>
              </div>
            )}
          </div>
        ))}
        {layout.adminEdit && (
          <Button variant="outline" size="sm" onClick={addKey}>
            <Plus className="h-4 w-4" /> Add integration key
          </Button>
        )}
        {layout.adminEdit && (
          <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
        )}
      </div>
    </div>
  )
}
