import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost, ApiError } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Checkbox } from '../../components/ui/checkbox'
import { Button } from '../../components/ui/button'
import { Dialog, DialogFooter, DialogTitle } from '../../components/ui/dialog'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface UserConfig {
  username: string
  ldap: boolean
  roles: string[]
  version?: string
  newPassword?: string
}

interface UserData {
  config: UserConfig
  allRoles: string[]
  ldapAvailable?: boolean
}

export function AdminUserPage() {
  const { layout } = useLayout()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const usernameParam = searchParams.get('username')
  const isNew = !usernameParam

  const [data, setData] = useState<UserData | null>(null)
  const [config, setConfig] = useState<UserConfig>({ username: '', ldap: false, roles: [] })
  const [originalConfig, setOriginalConfig] = useState<UserConfig>({ username: '', ldap: false, roles: [] })
  const [password, setPassword] = useState('')
  const [verifyPassword, setVerifyPassword] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [showNoRolesConfirm, setShowNoRolesConfirm] = useState(false)
  const noRolesConfirmResolverRef = useRef<((confirmed: boolean) => void) | null>(null)

  const isAnonymous = config.username.toLowerCase() === 'anonymous'

  const load = useCallback(async () => {
    try {
      if (isNew) {
        const resp = await apiGet<{ allRoles: string[]; ldapAvailable: boolean }>('/backend/admin/all-role-names')
        const sortedAllRoles = [...resp.allRoles].sort()
        setData({ config: { username: '', ldap: false, roles: [] }, allRoles: sortedAllRoles, ldapAvailable: resp.ldapAvailable })
        setConfig({ username: '', ldap: false, roles: [] })
        setOriginalConfig({ username: '', ldap: false, roles: [] })
      } else if (usernameParam) {
        const resp = await apiGet<UserData>('/backend/admin/users', { username: usernameParam })
        const sortedAllRoles = [...resp.allRoles].sort()
        const sortedConfig = { ...resp.config, roles: [...resp.config.roles].sort() }
        setData({ ...resp, allRoles: sortedAllRoles, config: sortedConfig })
        setConfig(sortedConfig)
        setOriginalConfig({ ...sortedConfig, roles: [...sortedConfig.roles] })
      }
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [isNew, usernameParam])

  useEffect(() => { load() }, [load])

  const hasChanges =
    JSON.stringify(config) !== JSON.stringify(originalConfig) ||
    password !== '' ||
    verifyPassword !== ''

  function toggleRole(role: string, checked: boolean) {
    const roles = checked
      ? [...config.roles, role].sort()
      : config.roles.filter((r) => r !== role)
    setConfig({ ...config, roles })
  }

  function requestNoRolesConfirmation(): Promise<boolean> {
    setShowNoRolesConfirm(true)
    return new Promise((resolve) => {
      noRolesConfirmResolverRef.current = resolve
    })
  }

  function resolveNoRolesConfirmation(confirmed: boolean) {
    setShowNoRolesConfirm(false)
    const resolve = noRolesConfirmResolverRef.current
    noRolesConfirmResolverRef.current = null
    resolve?.(confirmed)
  }

  async function handleSave(): Promise<void | false> {
    setSaveError(null)
    if (!isAnonymous && !config.ldap && password !== verifyPassword) {
      throw new Error('Passwords do not match')
    }
    if (config.roles.length === 0) {
      const confirmed = await requestNoRolesConfirmation()
      if (!confirmed) return false
    }
    const postData = {
      ...config,
      newPassword: !config.ldap && password ? password : undefined,
    }
    try {
      if (isNew) {
        const resp = await apiPost<UserData>('/backend/admin/users/add', postData)
        const sortedAllRoles = [...resp.allRoles].sort()
        const sortedConfig = { ...resp.config, roles: [...resp.config.roles].sort() }
        navigate(`/modern/admin/user?username=${encodeURIComponent(resp.config.username)}`, { replace: true })
        setData({ ...resp, allRoles: sortedAllRoles, config: sortedConfig })
        setConfig(sortedConfig)
        setOriginalConfig({ ...sortedConfig, roles: [...sortedConfig.roles] })
        setPassword('')
        setVerifyPassword('')
      } else {
        const resp = await apiPost<UserData>('/backend/admin/users/update', postData)
        const sortedAllRoles = [...resp.allRoles].sort()
        const sortedConfig = { ...resp.config, roles: [...resp.config.roles].sort() }
        setData({ ...resp, allRoles: sortedAllRoles, config: sortedConfig })
        setConfig(sortedConfig)
        setOriginalConfig({ ...sortedConfig, roles: [...sortedConfig.roles] })
        setPassword('')
        setVerifyPassword('')
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        throw new Error('There is already a user with this username')
      }
      throw e
    }
  }

  async function handleDelete() {
    const resp = await apiPost<{ errorCannotDeleteLastUser?: boolean }>(
      '/backend/admin/users/remove',
      { username: config.username || usernameParam },
    )
    if (resp.errorCannotDeleteLastUser) {
      throw new Error('Cannot delete last user')
    }
    navigate('/modern/admin/user-list', { replace: true })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />
  if (!data) return null

  const showPasswordFields = !isAnonymous && !config.ldap
  const passwordRequired = isNew || (originalConfig.ldap && !config.ldap)
  const heading = isNew ? '<New>' : isAnonymous ? '<anonymous>' : config.username

  return (
    <div>
      <div className="mb-3">
        <Link to="/modern/admin/user-list" className="text-sm text-blue-600 hover:underline">
          Return to list
        </Link>
      </div>
      <SettingsPage title={`Users — ${heading}`}>

        <GtFormGroup label="Username" required={!isAnonymous} value={config.username}>
          <Input
            value={config.username}
            onChange={(e) => setConfig({ ...config, username: e.target.value })}
            disabled={!isNew || !layout.adminEdit}
          />
        </GtFormGroup>

        {!isAnonymous && data.ldapAvailable && (
          <GtFormGroup label="LDAP">
            <label className="flex items-center gap-2 text-sm pt-2">
              <Checkbox
                checked={config.ldap}
                onCheckedChange={(c) => {
                  const ldap = !!c
                  setConfig({ ...config, ldap })
                  if (ldap) {
                    setPassword('')
                    setVerifyPassword('')
                  }
                }}
                disabled={!layout.adminEdit}
              />
              Use LDAP for password
            </label>
          </GtFormGroup>
        )}

        {showPasswordFields && (
          <>
            <GtFormGroup label="Password" required={passwordRequired} value={password}>
              <Input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={!layout.adminEdit}
              />
            </GtFormGroup>
            <GtFormGroup
              label="Verify password"
              required={passwordRequired || password !== ''}
              value={verifyPassword}
              invalid={verifyPassword !== '' && password !== verifyPassword}
              invalidMessage="Passwords do not match"
            >
              <Input
                type="password"
                value={verifyPassword}
                onChange={(e) => setVerifyPassword(e.target.value)}
                disabled={!layout.adminEdit}
              />
            </GtFormGroup>
          </>
        )}

        <GtFormGroup label="Roles">
          <div className="space-y-2 pt-1.5">
            {data.allRoles.map((role) => (
              <div key={role} className="flex items-center gap-2 text-sm">
                <Checkbox
                  checked={config.roles.includes(role)}
                  onCheckedChange={(c) => toggleRole(role, c)}
                  disabled={!layout.adminEdit}
                />
                <span>{role}</span>
                <Link
                  to={`/modern/admin/role?name=${encodeURIComponent(role)}`}
                  className="ml-1 text-xs text-blue-600 hover:underline"
                >
                  (view/edit)
                </Link>
              </div>
            ))}
            {/* Show roles that no longer exist */}
            {config.roles
              .filter((r) => !data.allRoles.includes(r))
              .map((role) => (
                <div key={role} className="flex items-center gap-2 text-sm">
                  <Checkbox
                    checked={true}
                    onCheckedChange={(c) => toggleRole(role, c)}
                    disabled={!layout.adminEdit}
                  />
                  <span className="text-red-600">{role} (role not found)</span>
                </div>
              ))}
          </div>
        </GtFormGroup>

        {saveError && (
          <p className="text-sm text-red-600 mb-3">{saveError}</p>
        )}

        {layout.adminEdit && (
          <GtButtonGroup
            onSave={handleSave}
            hasChanges={hasChanges}
            saveLabel={isNew ? 'Add' : 'Save changes'}
            onDelete={!isNew && config.version !== undefined ? handleDelete : undefined}
            showDelete={!isNew && config.version !== undefined}
            deleteConfirmHeader="Delete user"
            deleteConfirmMessage="Are you sure you want to delete this user?"
          />
        )}
      </SettingsPage>

      <Dialog open={showNoRolesConfirm} onClose={() => resolveNoRolesConfirmation(false)}>
        <DialogTitle>User has no role assignments</DialogTitle>
        <p className="mt-2 text-sm text-gray-600">
          Are you sure you want to save this user with no roles? Without any role assignments, this user will not be able to access anything in Glowroot.
        </p>
        <DialogFooter>
          <Button variant="default" onClick={() => resolveNoRolesConfirmation(true)}>
            Save
          </Button>
          <Button variant="outline" onClick={() => resolveNoRolesConfirmation(false)}>
            Cancel
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  )
}
