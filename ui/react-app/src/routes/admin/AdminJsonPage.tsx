import { useCallback, useEffect, useState } from 'react'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGetText, apiPostText } from '../../lib/api'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

export function AdminJsonPage() {
  const { layout } = useLayout()
  const canEdit = layout.adminEdit
  const [adminJson, setAdminJson] = useState('')
  const [originalAdminJson, setOriginalAdminJson] = useState('')
  const [configVersion, setConfigVersion] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  function processRawJson(raw: string) {
    const stripped = raw.replace(/,\s*"version":\s*"[0-9a-f]{40}"/, '')
    setAdminJson(stripped)
    setOriginalAdminJson(stripped)
    setConfigVersion(JSON.parse(raw).version)
  }

  const load = useCallback(async () => {
    try {
      const raw = await apiGetText('/backend/admin/json')
      processRawJson(raw)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const hasChanges = adminJson !== originalAdminJson

  async function handleSave() {
    let postData: Record<string, unknown>
    try {
      postData = JSON.parse(adminJson)
    } catch (e) {
      throw new Error('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)))
    }
    postData.version = configVersion
    const raw = await apiPostText('/backend/admin/json', postData)
    processRawJson(raw)
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  const lines = Math.max((adminJson.match(/\n/g) || []).length + 1, 10)

  return (
    <div className="rounded-lg border bg-white p-6">
      <h2 className="text-lg font-semibold text-gray-900 mb-4">admin.json</h2>
      <textarea
        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm font-mono"
        rows={lines}
        value={adminJson}
        onChange={(e) => setAdminJson(e.target.value)}
        disabled={!canEdit}
      />
      <p className="mt-2 text-xs text-gray-500">
        User passwords can be set by adding a &quot;password&quot; attribute (which takes a plain password) or by adding a
        &quot;hashedPassword&quot; attribute (which takes a hashed password).
        {layout.central && (
          <>
            {' '}If needed, user passwords can be hashed manually by running{' '}
            <code>java -jar glowroot-central.jar hash-password &lt;plain password&gt;</code>.
          </>
        )}
      </p>
      <p className="mt-1 text-xs text-gray-500">
        SMTP, HTTP Proxy and LDAP passwords can be set by adding a &quot;password&quot; attribute (which takes a plain
        password) or by adding an &quot;encryptedPassword&quot; attribute (which takes an encrypted password).
        {layout.central && (
          <>
            {' '}If needed, SMTP, HTTP Proxy and LDAP passwords can be encrypted manually by running{' '}
            <code>java -jar glowroot-central.jar encrypt-password &lt;plain password&gt;</code>.
          </>
        )}
      </p>
      {canEdit && <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />}
    </div>
  )
}
