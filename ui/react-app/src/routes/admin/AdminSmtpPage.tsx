import { useCallback, useEffect, useState } from 'react'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost, generateEncryptionKeyMessage } from '../../lib/api'
import { PATTERNS } from '../../lib/validation'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Select } from '../../components/ui/select'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface SmtpConfig {
  host?: string
  port?: number
  connectionSecurity?: string | null
  username?: string
  passwordExists?: boolean
  newPassword?: string
  fromEmailAddress?: string
  fromDisplayName?: string
  version?: string
}

interface SmtpData {
  config: SmtpConfig
  localServerName?: string
}

export function AdminSmtpPage() {
  const { layout } = useLayout()
  const [data, setData] = useState<SmtpData | null>(null)
  const [config, setConfig] = useState<SmtpConfig>({})
  const [originalConfig, setOriginalConfig] = useState<SmtpConfig>({})
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [testEmail, setTestEmail] = useState('')
  const [testMessage, setTestMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const [testing, setTesting] = useState(false)

  function onNewData(resp: SmtpData) {
    setData(resp)
    setConfig({ ...resp.config })
    setOriginalConfig({ ...resp.config })
    setPassword(resp.config.passwordExists ? '********' : '')
  }

  const load = useCallback(async () => {
    try {
      const resp = await apiGet<SmtpData>('/backend/admin/smtp')
      onNewData(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const hasChanges = JSON.stringify(config) !== JSON.stringify(originalConfig)

  function handlePasswordChange(value: string) {
    setPassword(value)
    setConfig({
      ...config,
      newPassword: value,
      passwordExists: value !== '',
    })
  }

  async function handleSave() {
    const resp = await apiPost<SmtpData & { symmetricEncryptionKeyMissing?: boolean }>(
      '/backend/admin/smtp',
      config,
    )
    if (resp.symmetricEncryptionKeyMissing) {
      throw new Error(generateEncryptionKeyMessage('SMTP password can be saved to cassandra'))
    }
    onNewData(resp)
  }

  async function handleSendTest() {
    setTestMessage(null)
    if (!testEmail) {
      setTestMessage({ type: 'error', text: 'Test email recipient is required' })
      return
    }
    if (!config.host) {
      setTestMessage({ type: 'error', text: 'Host is required' })
      return
    }
    setTesting(true)
    try {
      const postData = { ...config, testEmailRecipient: testEmail }
      const resp = await apiPost<{ error?: boolean; message?: string }>(
        '/backend/admin/send-test-email', postData
      )
      if (resp.error) {
        setTestMessage({ type: 'error', text: resp.message || 'Failed to send test email' })
      } else {
        setTestMessage({ type: 'success', text: resp.message || 'Sent' })
      }
    } catch (e) {
      setTestMessage({ type: 'error', text: e instanceof Error ? e.message : 'Failed to send test email' })
    } finally {
      setTesting(false)
    }
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  return (
    <SettingsPage title="SMTP">
      <GtFormGroup label="Host" helpText="SMTP server hostname or IP address.">
        <Input
          value={config.host ?? ''}
          onChange={(e) => setConfig({ ...config, host: e.target.value })}
          placeholder="SMTP server hostname or IP address"
          disabled={!layout.adminEdit}
        />
      </GtFormGroup>

      <GtFormGroup label="Port" pattern={PATTERNS.integer} value={config.port ?? ''}>
        <Input
          type="number"
          value={config.port ?? ''}
          onChange={(e) => {
            const val = e.target.value
            setConfig({ ...config, port: val === '' ? undefined : parseInt(val, 10) })
          }}
          placeholder="25"
          disabled={!layout.adminEdit}
        />
      </GtFormGroup>

      <GtFormGroup label="Connection security">
        <Select
          value={config.connectionSecurity ?? ''}
          onChange={(e) => setConfig({ ...config, connectionSecurity: e.target.value || null })}
          disabled={!layout.adminEdit}
        >
          <option value="">None</option>
          <option value="ssl-tls">SSL/TLS</option>
          <option value="starttls">STARTTLS</option>
        </Select>
      </GtFormGroup>

      <GtFormGroup label="Username">
        <Input
          value={config.username ?? ''}
          onChange={(e) => setConfig({ ...config, username: e.target.value })}
          disabled={!layout.adminEdit}
        />
      </GtFormGroup>

      <GtFormGroup label="Password">
        <Input
          type="password"
          value={password}
          onChange={(e) => handlePasswordChange(e.target.value)}
          onClick={(e) => {
            if (password === '********') {
              e.currentTarget.select()
            }
          }}
          onFocus={(e) => {
            if (password === '********') {
              e.target.select()
            }
          }}
          disabled={!layout.adminEdit}
        />
      </GtFormGroup>

      <GtFormGroup label="From email address">
        <Input
          value={config.fromEmailAddress ?? ''}
          onChange={(e) => setConfig({ ...config, fromEmailAddress: e.target.value })}
          placeholder={data?.localServerName ? `glowroot@${data.localServerName}` : ''}
          disabled={!layout.adminEdit}
        />
      </GtFormGroup>

      <GtFormGroup label="From display name">
        <Input
          value={config.fromDisplayName ?? ''}
          onChange={(e) => setConfig({ ...config, fromDisplayName: e.target.value })}
          placeholder="Glowroot"
          disabled={!layout.adminEdit}
        />
      </GtFormGroup>

      {layout.adminEdit && (
        <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
      )}

      {layout.adminEdit && (
        <div className="mt-8 border-t pt-6">
          <h3 className="text-sm font-semibold text-gray-900 mb-4">Send test email</h3>
          <GtFormGroup label="Test email recipient">
            <Input
              value={testEmail}
              onChange={(e) => setTestEmail(e.target.value)}
              placeholder="recipient@example.com"
            />
          </GtFormGroup>
          <div className="flex items-center gap-3 pl-[33.333%]">
            <Button
              variant="secondary"
              onClick={handleSendTest}
              loading={testing}
              disabled={!testEmail || !config.host}
            >
              Send test email
            </Button>
            {testMessage && (
              <span className={`text-sm font-medium ${testMessage.type === 'success' ? 'text-green-600' : 'text-red-600'}`}>
                {testMessage.text}
              </span>
            )}
          </div>
        </div>
      )}
    </SettingsPage>
  )
}
