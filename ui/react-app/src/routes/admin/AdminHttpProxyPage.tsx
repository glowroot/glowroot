import { useCallback, useEffect, useState } from 'react'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost, generateEncryptionKeyMessage } from '../../lib/api'
import { PATTERNS } from '../../lib/validation'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Textarea } from '../../components/ui/textarea'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface HttpProxyConfig {
  host?: string
  port?: number
  username?: string
  passwordExists?: boolean
  newPassword?: string
  version?: string
}

export function AdminHttpProxyPage() {
  const { layout } = useLayout()
  const [config, setConfig] = useState<HttpProxyConfig>({})
  const [originalConfig, setOriginalConfig] = useState<HttpProxyConfig>({})
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [testUrl, setTestUrl] = useState('')
  const [testResponse, setTestResponse] = useState<string | null>(null)
  const [testError, setTestError] = useState<string | null>(null)
  const [testing, setTesting] = useState(false)

  function onNewData(data: HttpProxyConfig) {
    setConfig({ ...data })
    setOriginalConfig({ ...data })
    setPassword(data.passwordExists ? '********' : '')
  }

  const load = useCallback(async () => {
    try {
      const resp = await apiGet<HttpProxyConfig>('/backend/admin/http-proxy')
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
    setTestResponse(null)
    setTestError(null)
    const resp = await apiPost<HttpProxyConfig & { symmetricEncryptionKeyMissing?: boolean }>(
      '/backend/admin/http-proxy',
      config,
    )
    if (resp.symmetricEncryptionKeyMissing) {
      throw new Error(generateEncryptionKeyMessage('HTTP proxy password can be saved to cassandra'))
    }
    onNewData(resp)
  }

  async function handleTest() {
    setTestResponse(null)
    setTestError(null)
    if (!testUrl) {
      setTestError('Test URL is required')
      return
    }
    if (!config.host) {
      setTestError('Host is required')
      return
    }
    setTesting(true)
    try {
      const postData = { ...config, testUrl }
      const resp = await apiPost<{ error?: boolean; message?: string; content?: string }>(
        '/backend/admin/test-http-proxy', postData
      )
      if (resp.error) {
        setTestError(resp.message || 'Test failed')
      } else {
        setTestResponse(resp.content || 'Success')
      }
    } catch (e) {
      setTestError(e instanceof Error ? e.message : 'Test failed')
    } finally {
      setTesting(false)
    }
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  return (
    <SettingsPage title="HTTP proxy">
      <GtFormGroup label="Host" helpText="HTTP Proxy server hostname or IP address.">
        <Input
          value={config.host ?? ''}
          onChange={(e) => setConfig({ ...config, host: e.target.value })}
          placeholder="HTTP Proxy server hostname or IP address"
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
          placeholder="80"
          disabled={!layout.adminEdit}
        />
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

      {layout.adminEdit && (
        <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
      )}

      {layout.adminEdit && (
        <div className="mt-8 border-t pt-6">
          <h3 className="text-sm font-semibold text-gray-900 mb-4">Test HTTP proxy</h3>
          <GtFormGroup label="Test URL">
            <Input
              value={testUrl}
              onChange={(e) => setTestUrl(e.target.value)}
              placeholder="https://example.com"
            />
          </GtFormGroup>
          <div className="flex items-center gap-3 pl-[33.333%]">
            <Button
              variant="secondary"
              onClick={handleTest}
              loading={testing}
              disabled={!testUrl || !config.host}
            >
              Test
            </Button>
          </div>
          {testError && (
            <div className="mt-3 pl-[33.333%]">
              <p className="text-sm text-red-600">{testError}</p>
            </div>
          )}
          {testResponse && (
            <div className="mt-3 pl-[33.333%]">
              <Textarea
                value={testResponse}
                readOnly
                rows={6}
                className="font-mono text-xs"
              />
            </div>
          )}
        </div>
      )}
    </SettingsPage>
  )
}
