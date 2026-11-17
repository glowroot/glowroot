import { useCallback, useEffect, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface Webhook {
  url: string
  display: string
}

interface SlackConfig {
  webhooks?: Webhook[]
  version?: string
}

export function AdminSlackPage() {
  const { layout } = useLayout()
  const [config, setConfig] = useState<SlackConfig>({})
  const [originalConfig, setOriginalConfig] = useState<SlackConfig>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  function onNewData(data: SlackConfig) {
    setConfig({ ...data, webhooks: data.webhooks?.map(w => ({ ...w })) ?? [] })
    setOriginalConfig({ ...data, webhooks: data.webhooks?.map(w => ({ ...w })) ?? [] })
  }

  const load = useCallback(async () => {
    try {
      const resp = await apiGet<SlackConfig>('/backend/admin/slack')
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
    const resp = await apiPost<
      SlackConfig & {
        duplicateWebhookUrl?: boolean
        duplicateWebhookDisplay?: boolean
      }
    >('/backend/admin/slack', config)
    if (resp.duplicateWebhookUrl) {
      throw new Error('Duplicate webhook url found')
    }
    if (resp.duplicateWebhookDisplay) {
      throw new Error('Duplicate webhook display found')
    }
    onNewData(resp)
  }

  function addWebhook() {
    setConfig({ ...config, webhooks: [...(config.webhooks ?? []), { url: '', display: '' }] })
  }

  function removeWebhook(index: number) {
    const updated = [...(config.webhooks ?? [])]
    updated.splice(index, 1)
    setConfig({ ...config, webhooks: updated })
  }

  function updateWebhook(index: number, field: keyof Webhook, value: string) {
    const updated = [...(config.webhooks ?? [])]
    updated[index] = { ...updated[index], [field]: value }
    setConfig({ ...config, webhooks: updated })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  return (
    <div>
      <SettingsPage
        title="Slack"
        back={{ to: '/modern/admin/integration-list', label: 'Integrations' }}
      >
        <h3 className="text-sm font-semibold text-gray-900 mb-4">Slack webhooks</h3>
        {(config.webhooks ?? []).map((wh, i) => (
          <div key={i} className="mb-4 rounded border p-4 bg-gray-50">
            <GtFormGroup label="Webhook URL" required value={wh.url}>
              <Input value={wh.url} onChange={(e) => updateWebhook(i, 'url', e.target.value)} disabled={!layout.adminEdit} />
            </GtFormGroup>
            <GtFormGroup label="Display" required value={wh.display}>
              <Input value={wh.display} onChange={(e) => updateWebhook(i, 'display', e.target.value)} disabled={!layout.adminEdit} />
            </GtFormGroup>
            {layout.adminEdit && (
              <div className="flex justify-end">
                <Button variant="ghost" size="sm" onClick={() => removeWebhook(i)}>
                  <Trash2 className="h-4 w-4" /> Remove
                </Button>
              </div>
            )}
          </div>
        ))}
        {layout.adminEdit && (
          <Button variant="outline" size="sm" onClick={addWebhook}>
            <Plus className="h-4 w-4" /> Add webhook
          </Button>
        )}
        {layout.adminEdit && (
          <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
        )}
      </SettingsPage>
    </div>
  )
}
