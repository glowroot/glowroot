import { useCallback, useEffect, useState } from 'react'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost } from '../../lib/api'
import { PATTERNS } from '../../lib/validation'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Checkbox } from '../../components/ui/checkbox'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface WebConfig {
  port?: number
  bindAddress?: string
  https?: boolean
  contextPath?: string
  sessionTimeoutMinutes?: number
  sessionCookieName?: string
  version?: string
}

interface WebData {
  config: WebConfig
  activePort?: number
  activeBindAddress?: string
  activeHttps?: boolean
  portReadOnly?: boolean
  confDirs?: string[]
}

export function AdminWebPage() {
  const { layout } = useLayout()
  const [data, setData] = useState<WebData | null>(null)
  const [config, setConfig] = useState<WebConfig>({})
  const [originalConfig, setOriginalConfig] = useState<WebConfig>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    try {
      const resp = await apiGet<WebData>('/backend/admin/web')
      setData(resp)
      setConfig({ ...resp.config })
      setOriginalConfig({ ...resp.config })
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const hasChanges = JSON.stringify(config) !== JSON.stringify(originalConfig)

  async function handleSave() {
    const postData = { ...config }
    if (!layout.central && (!postData.port || postData.port <= 0) && data?.activePort) {
      postData.port = data.activePort
    }
    const previousActivePort = data?.activePort
    const changingPort = !layout.central && postData.port !== data?.activePort
    const changingHttps = !layout.central && postData.https !== data?.activeHttps
    const resp = await apiPost<
      WebData & {
        httpsRequiredFilesDoNotExist?: boolean
        httpsValidationError?: string
        portChangeFailed?: boolean
      }
    >('/backend/admin/web', postData)
    if (resp.httpsRequiredFilesDoNotExist) {
      throw new Error(
        'The certificate and private key to be used must be placed in the glowroot directory with filenames ui-cert.pem and ui-key.pem before enabling HTTPS',
      )
    }
    if (resp.httpsValidationError) {
      throw new Error(resp.httpsValidationError)
    }
    if (resp.portChangeFailed) {
      throw new Error('Save succeeded, but switching over to the new port failed')
    }
    setData(resp)
    setConfig({ ...resp.config })
    setOriginalConfig({ ...resp.config })
    // If port or https changed, redirect to new URL
    if (!layout.central && (changingPort || changingHttps)) {
      const currentPort =
        window.location.port || (window.location.protocol === 'https:' ? '443' : '80')
      const text =
        changingPort && !changingHttps
          ? 'port'
          : !changingPort && changingHttps
            ? 'protocol'
            : 'port and protocol'
      if (previousActivePort !== undefined && currentPort !== String(previousActivePort)) {
        throw new Error(
          `The save succeeded, and switching the http listener over to the new port succeeded, but you are not being redirected to the new ${text} since it seems you are using an intermediary proxy?`,
        )
      }
      const protocol = resp.activeHttps ? 'https' : 'http'
      const rawCtx = resp.config.contextPath ?? '/'
      const ctxPrefix = rawCtx === '/' ? '' : rawCtx.replace(/\/+$/, '')
      const defaultPort = resp.activeHttps ? 443 : 80
      const portPart = resp.activePort && resp.activePort !== defaultPort ? `:${resp.activePort}` : ''
      const newUrl = `${protocol}://${window.location.hostname}${portPart}${ctxPrefix}/modern/admin/web`
      setTimeout(() => {
        window.location.href = newUrl
      }, 500)
    }
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!data) return null

  const confDirs = data.confDirs ?? []
  const confDirText =
    confDirs.length === 1
      ? confDirs[0]
      : confDirs.length === 2
        ? `either ${confDirs[0]} or ${confDirs[1]}`
        : confDirs.length === 3
          ? `either ${confDirs[0]}, ${confDirs[1]} or ${confDirs[2]}`
          : confDirs.length >= 4
            ? `either ${confDirs[0]}, ${confDirs[1]}, ${confDirs[2]} or ${confDirs[3]}`
            : 'the glowroot directory'

  return (
    <SettingsPage title="Web">
      {!layout.central && (
        <>
          <GtFormGroup
            label="Port"
            required
            pattern={PATTERNS.integer}
            value={config.port ?? ''}
          >
            <Input
              type="number"
              value={config.port ?? ''}
              onChange={(e) => {
                const val = e.target.value
                setConfig({ ...config, port: val === '' ? undefined : parseInt(val, 10) })
              }}
              disabled={!layout.adminEdit || data.portReadOnly}
            />
            {originalConfig.port !== data.activePort && data.activePort !== undefined && (
              <p className="mt-1 text-xs text-amber-600">
                Active value is <code>{data.activePort}</code>.
              </p>
            )}
            <p className="mt-1 text-xs text-gray-500">
              Port that the user interface listens on.
              {layout.adminEdit && data.portReadOnly && (
                <>
                  {' '}The port is read-only because it has been set via the jvm arg{' '}
                  <code>-Dglowroot.agent.port={config.port}</code>.
                </>
              )}
            </p>
          </GtFormGroup>

          <GtFormGroup
            label="Bind address"
            required
            value={config.bindAddress ?? ''}
          >
            <Input
              value={config.bindAddress ?? ''}
              onChange={(e) => setConfig({ ...config, bindAddress: e.target.value })}
              disabled={!layout.adminEdit}
            />
            {originalConfig.bindAddress !== data.activeBindAddress && data.activeBindAddress !== undefined && (
              <p className="mt-1 text-xs text-amber-600">
                This change requires JVM restart. Active value is <code>{data.activeBindAddress}</code>.
              </p>
            )}
            <p className="mt-1 text-xs text-gray-500">
              Address that the user interface listens on.
              {originalConfig.bindAddress === '127.0.0.1' && (
                <> To access the UI from remote machines, change the bind address to 0.0.0.0</>
              )}
            </p>
          </GtFormGroup>

          <GtFormGroup label="HTTPS">
            <label className="flex items-center gap-2 text-sm pt-2">
              <Checkbox
                checked={config.https ?? false}
                onCheckedChange={(checked) => setConfig({ ...config, https: checked })}
                disabled={!layout.adminEdit}
              />
              Use HTTPS
            </label>
            {originalConfig.https !== data.activeHttps && data.activeHttps !== undefined && (
              <p className="mt-1 text-xs text-amber-600">
                Active value is <code>{String(data.activeHttps)}</code>.
              </p>
            )}
            <p className="mt-1 text-xs text-gray-500">
              Serve the user interface over HTTPS. The certificate and private key to be used must be placed in{' '}
              <em>{confDirText}</em> with filenames <em>ui-cert.pem</em> and <em>ui-key.pem</em> before enabling HTTPS,
              where ui-cert.pem is a PEM encoded X.509 certificate chain, and ui-key.pem is a PEM encoded PKCS#8 private key
              without a passphrase. (for example, a self signed certificate can be generated at the command line meeting the
              above requirements using OpenSSL 1.0.0 or later:{' '}
              <code>openssl req -new -x509 -nodes -days 365 -out ui-cert.pem -keyout ui-key.pem</code>)
            </p>
          </GtFormGroup>

          <GtFormGroup
            label="Context path"
            required
            pattern={/^\/$|^\/.*[^/]$/}
            value={config.contextPath ?? ''}
            helpText="This only needs to be changed if reverse proxying the user interface behind a non-root context path."
          >
            <Input
              value={config.contextPath ?? ''}
              onChange={(e) => setConfig({ ...config, contextPath: e.target.value })}
              disabled={!layout.adminEdit}
            />
          </GtFormGroup>
        </>
      )}

      <GtFormGroup
        label="Session timeout (minutes)"
        required
        pattern={PATTERNS.integer}
        value={config.sessionTimeoutMinutes ?? ''}
      >
        <Input
          type="number"
          value={config.sessionTimeoutMinutes ?? ''}
          onChange={(e) => {
            const val = e.target.value
            setConfig({ ...config, sessionTimeoutMinutes: val === '' ? undefined : parseInt(val, 10) })
          }}
          disabled={!layout.adminEdit || !layout.loginEnabled}
        />
        <p className="mt-1 text-xs text-gray-500">
          {layout.adminEdit && !layout.loginEnabled && (
            <>The session timeout is disabled because there are no users configured. </>
          )}
          Session timeout <code>0</code> means sessions do not time out, except on jvm restart at which time all sessions time out.
        </p>
      </GtFormGroup>

      <GtFormGroup
        label="Session cookie name"
        required
        pattern={/^[a-zA-Z0-9_]*$/}
        value={config.sessionCookieName ?? ''}
        helpText={
          layout.adminEdit && !layout.loginEnabled
            ? 'The session cookie name is disabled because there are no users configured.'
            : undefined
        }
      >
        <Input
          value={config.sessionCookieName ?? ''}
          onChange={(e) => setConfig({ ...config, sessionCookieName: e.target.value })}
          disabled={!layout.adminEdit || !layout.loginEnabled}
        />
      </GtFormGroup>

      {layout.adminEdit && (
        <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
      )}
    </SettingsPage>
  )
}
