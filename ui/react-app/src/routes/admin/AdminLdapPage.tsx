import { useCallback, useEffect, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost, generateEncryptionKeyMessage } from '../../lib/api'
import { PATTERNS } from '../../lib/validation'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { Input } from '../../components/ui/input'
import { Checkbox } from '../../components/ui/checkbox'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'
import { SettingsSection } from '../../components/shared/SettingsSection'

interface RoleMapping {
  ldapGroupDn: string
  glowrootRoles: string[]
  rawRolesText: string
}

interface LdapConfig {
  host?: string
  port?: number
  ssl?: boolean
  username?: string
  passwordExists?: boolean
  newPassword?: string
  userBaseDn?: string
  userSearchFilter?: string
  groupBaseDn?: string
  groupSearchFilter?: string
  roleMappings?: Record<string, string[]>
  version?: string
}

interface LdapData {
  config: LdapConfig
  allGlowrootRoles?: string[]
}

function mapToBlocks(roleMappings?: Record<string, string[]>): RoleMapping[] {
  if (!roleMappings) return []
  return Object.entries(roleMappings).map(([ldapGroupDn, glowrootRoles]) => ({
    ldapGroupDn,
    glowrootRoles: [...glowrootRoles],
    rawRolesText: glowrootRoles.join(', '),
  }))
}

function blocksToMap(blocks: RoleMapping[]): Record<string, string[]> {
  const map: Record<string, string[]> = {}
  for (const block of blocks) {
    map[block.ldapGroupDn] = block.glowrootRoles
  }
  return map
}

export function AdminLdapPage() {
  const { layout } = useLayout()
  const [config, setConfig] = useState<LdapConfig>({})
  const [originalConfig, setOriginalConfig] = useState<LdapConfig>({})
  const [allGlowrootRoles, setAllGlowrootRoles] = useState<string[]>([])
  const [roleMappingBlocks, setRoleMappingBlocks] = useState<RoleMapping[]>([])
  const [originalRoleMappingBlocks, setOriginalRoleMappingBlocks] = useState<RoleMapping[]>([])
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [testUsername, setTestUsername] = useState('')
  const [testPassword, setTestPassword] = useState('')
  const [testResult, setTestResult] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const [testing, setTesting] = useState(false)

  function onNewData(resp: LdapData) {
    setConfig({ ...resp.config })
    setOriginalConfig({ ...resp.config })
    setAllGlowrootRoles(resp.allGlowrootRoles ?? [])
    const blocks = mapToBlocks(resp.config.roleMappings)
    setRoleMappingBlocks(blocks)
    setOriginalRoleMappingBlocks(blocks.map(b => ({ ...b, glowrootRoles: [...b.glowrootRoles] })))
    setPassword(resp.config.passwordExists ? '********' : '')
  }

  const load = useCallback(async () => {
    try {
      const resp = await apiGet<LdapData>('/backend/admin/ldap')
      onNewData(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const hasChanges = JSON.stringify(config) !== JSON.stringify(originalConfig)
    || JSON.stringify(roleMappingBlocks) !== JSON.stringify(originalRoleMappingBlocks)

  function handlePasswordChange(value: string) {
    setPassword(value)
    setConfig({ ...config, newPassword: value, passwordExists: value !== '' })
  }

  async function handleSave() {
    const postData = { ...config, roleMappings: blocksToMap(roleMappingBlocks) }
    const resp = await apiPost<LdapData & { symmetricEncryptionKeyMissing?: boolean }>(
      '/backend/admin/ldap',
      postData,
    )
    if (resp.symmetricEncryptionKeyMissing) {
      throw new Error(generateEncryptionKeyMessage('LDAP password can be saved to cassandra'))
    }
    onNewData(resp)
  }

  function addRoleMapping() {
    setRoleMappingBlocks([...roleMappingBlocks, { ldapGroupDn: '', glowrootRoles: [], rawRolesText: '' }])
  }

  function removeRoleMapping(index: number) {
    const updated = [...roleMappingBlocks]
    updated.splice(index, 1)
    setRoleMappingBlocks(updated)
  }

  function updateRoleMapping(index: number, field: keyof RoleMapping, value: string | string[]) {
    const updated = [...roleMappingBlocks]
    updated[index] = { ...updated[index], [field]: value }
    setRoleMappingBlocks(updated)
  }

  function toggleMappedRole(index: number, role: string, checked: boolean) {
    const current = roleMappingBlocks[index].glowrootRoles
    const next = checked
      ? (current.includes(role) ? current : [...current, role])
      : current.filter((r) => r !== role)
    const updated = [...roleMappingBlocks]
    updated[index] = { ...updated[index], glowrootRoles: next, rawRolesText: next.join(', ') }
    setRoleMappingBlocks(updated)
  }

  async function handleTest() {
    setTestResult(null)
    if (!testUsername) {
      setTestResult({ type: 'error', text: 'Test username is required' })
      return
    }
    if (!testPassword) {
      setTestResult({ type: 'error', text: 'Test password is required' })
      return
    }
    if (!config.host) {
      setTestResult({ type: 'error', text: 'Host is required' })
      return
    }
    setTesting(true)
    try {
      const postData = { ...config, roleMappings: blocksToMap(roleMappingBlocks), authTestUsername: testUsername, authTestPassword: testPassword }
      const resp = await apiPost<{
        error?: boolean
        message?: string
        glowrootRoles?: string[]
        ldapGroupDns?: string[]
      }>('/backend/admin/test-ldap', postData)
      if (resp.error) {
        setTestResult({ type: 'error', text: resp.message || 'Test failed' })
      } else if (resp.glowrootRoles && resp.glowrootRoles.length > 0) {
        setTestResult({
          type: 'success',
          text: 'Success, assigned to Glowroot roles: ' + resp.glowrootRoles.join(', '),
        })
      } else if (resp.ldapGroupDns && resp.ldapGroupDns.length > 0) {
        setTestResult({
          type: 'error',
          text:
            'Authentication succeeded, but the LDAP group query did not find any groups for this user' +
            ' that have been mapped to Glowroot roles above. The LDAP groups query found the following groups:\n\n' +
            resp.ldapGroupDns.join('\n\n'),
        })
      } else {
        setTestResult({
          type: 'error',
          text: 'Authentication succeeded, but the LDAP group query did not find any groups for this user',
        })
      }
    } catch (e) {
      setTestResult({ type: 'error', text: e instanceof Error ? e.message : 'Test failed' })
    } finally {
      setTesting(false)
    }
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  const hostRequired = Boolean(config.host?.trim())

  return (
    <SettingsPage
      title="LDAP"
      back={{ to: '/modern/admin/integration-list', label: 'Integrations' }}
    >
      <SettingsSection first title="Connection">
        <GtFormGroup label="Host" helpText="LDAP server hostname or IP address.">
          <Input value={config.host ?? ''} onChange={(e) => setConfig({ ...config, host: e.target.value })} disabled={!layout.adminEdit} />
        </GtFormGroup>
        <GtFormGroup
          label="Port"
          pattern={PATTERNS.integer}
          value={config.port ?? ''}
          helpText="Defaults to port 389 for non-SSL, and 636 for SSL."
        >
          <Input
            type="number"
            value={config.port ?? ''}
            onChange={(e) => {
              const val = e.target.value
              setConfig({ ...config, port: val === '' ? undefined : parseInt(val, 10) })
            }}
            disabled={!layout.adminEdit}
          />
        </GtFormGroup>
        <GtFormGroup label="SSL">
          <label className="flex items-center gap-2 text-sm pt-2">
            <Checkbox checked={config.ssl ?? false} onCheckedChange={(c) => setConfig({ ...config, ssl: c })} disabled={!layout.adminEdit} />
            Use SSL
          </label>
        </GtFormGroup>
        <GtFormGroup
          label="Username"
          required={hostRequired}
          value={config.username ?? ''}
          helpText="Username that Glowroot uses to bind and run LDAP queries."
        >
          <Input value={config.username ?? ''} onChange={(e) => setConfig({ ...config, username: e.target.value })} disabled={!layout.adminEdit} />
        </GtFormGroup>
        <GtFormGroup
          label="Password"
          required={hostRequired}
          value={password}
          helpText="Password that Glowroot uses to bind and run LDAP queries."
        >
          <Input
            type="password"
            value={password}
            onChange={(e) => handlePasswordChange(e.target.value)}
            onClick={(e) => { if (password === '********') e.currentTarget.select() }}
            onFocus={(e) => { if (password === '********') e.target.select() }}
            disabled={!layout.adminEdit}
          />
        </GtFormGroup>
      </SettingsSection>

      <SettingsSection title="User DN structure">
        <GtFormGroup
          label="User base DN"
          required={hostRequired}
          value={config.userBaseDn ?? ''}
          helpText="Base DN for locating users, e.g. ou=Users,dc=example,dc=com"
        >
          <Input value={config.userBaseDn ?? ''} onChange={(e) => setConfig({ ...config, userBaseDn: e.target.value })} disabled={!layout.adminEdit} />
        </GtFormGroup>
        <GtFormGroup
          label="User search filter"
          required={hostRequired}
          value={config.userSearchFilter ?? ''}
          helpText="This search filter is used to find the user based on the username they enter during login. The placeholder {0} in the filter will be populated with the username. For active directory this filter is likely sAMAccountName={0}"
        >
          <Input value={config.userSearchFilter ?? ''} onChange={(e) => setConfig({ ...config, userSearchFilter: e.target.value })} disabled={!layout.adminEdit} />
        </GtFormGroup>
        <GtFormGroup
          label="Group base DN"
          required={hostRequired}
          value={config.groupBaseDn ?? ''}
          helpText="Base DN for locating groups, e.g. ou=Groups,dc=example,dc=com"
        >
          <Input value={config.groupBaseDn ?? ''} onChange={(e) => setConfig({ ...config, groupBaseDn: e.target.value })} disabled={!layout.adminEdit} />
        </GtFormGroup>
        <GtFormGroup
          label="Group search filter"
          required={hostRequired}
          value={config.groupSearchFilter ?? ''}
          helpText="This search filter is used to find the groups that a user is a member of. The placeholder {0} in the filter will be populated with the user DN. For active directory this filter is likely member={0}"
        >
          <Input value={config.groupSearchFilter ?? ''} onChange={(e) => setConfig({ ...config, groupSearchFilter: e.target.value })} disabled={!layout.adminEdit} />
        </GtFormGroup>
      </SettingsSection>

      <SettingsSection title="Role mappings">
        {roleMappingBlocks.map((mapping, i) => (
          <div key={i} className="mb-4 rounded border p-4 bg-gray-50">
            <GtFormGroup
              label="LDAP group DN"
              required
              value={mapping.ldapGroupDn}
              helpText="Full distinguished name (DN)."
            >
              <Input value={mapping.ldapGroupDn} onChange={(e) => updateRoleMapping(i, 'ldapGroupDn', e.target.value)} disabled={!layout.adminEdit} />
            </GtFormGroup>
            <GtFormGroup
              label="Glowroot roles"
              required
              value={mapping.glowrootRoles.length > 0 ? 'selected' : ''}
            >
              {allGlowrootRoles.length > 0 ? (
                <div className="space-y-1.5">
                  {allGlowrootRoles.map((role) => (
                    <label key={role} className="flex items-center gap-2 text-sm">
                      <Checkbox
                        checked={mapping.glowrootRoles.includes(role)}
                        onCheckedChange={(c) => toggleMappedRole(i, role, !!c)}
                        disabled={!layout.adminEdit}
                      />
                      {role}
                    </label>
                  ))}
                  {mapping.glowrootRoles
                    .filter((r) => !allGlowrootRoles.includes(r))
                    .map((role) => (
                      <label key={role} className="flex items-center gap-2 text-sm">
                        <Checkbox
                          checked={true}
                          onCheckedChange={(c) => toggleMappedRole(i, role, !!c)}
                          disabled={!layout.adminEdit}
                        />
                        <span className="text-red-600">{role} (role not found)</span>
                      </label>
                    ))}
                </div>
              ) : (
                <Input
                  value={mapping.rawRolesText}
                  onChange={(e) => {
                    const text = e.target.value
                    const parsed = text.split(',').map((s) => s.trim()).filter(Boolean)
                    const updated = [...roleMappingBlocks]
                    updated[i] = { ...updated[i], rawRolesText: text, glowrootRoles: parsed }
                    setRoleMappingBlocks(updated)
                  }}
                  placeholder="Comma-separated role names"
                  disabled={!layout.adminEdit}
                />
              )}
            </GtFormGroup>
            {layout.adminEdit && (
              <div className="flex justify-end">
                <Button variant="ghost" size="sm" onClick={() => removeRoleMapping(i)}>
                  <Trash2 className="h-4 w-4" /> Remove
                </Button>
              </div>
            )}
          </div>
        ))}
        {layout.adminEdit && (
          <Button variant="outline" size="sm" onClick={addRoleMapping}>
            <Plus className="h-4 w-4" /> Add role mapping
          </Button>
        )}
      </SettingsSection>

      {layout.adminEdit && (
        <GtButtonGroup onSave={handleSave} hasChanges={hasChanges} />
      )}

      {layout.adminEdit && (
        <SettingsSection title="Test LDAP">
          <GtFormGroup label="Username">
            <Input value={testUsername} onChange={(e) => setTestUsername(e.target.value)} />
          </GtFormGroup>
          <GtFormGroup label="Password">
            <Input type="password" value={testPassword} onChange={(e) => setTestPassword(e.target.value)} />
          </GtFormGroup>
          <div className="flex items-center gap-3 sm:ml-56">
            <Button variant="secondary" onClick={handleTest} loading={testing}>
              Test
            </Button>
          </div>
          {testResult && (
            <div className={`mt-3 sm:ml-56 text-sm font-medium whitespace-pre-line ${testResult.type === 'success' ? 'text-green-600' : 'text-red-600'}`}>
              {testResult.text}
            </div>
          )}
        </SettingsSection>
      )}
    </SettingsPage>
  )
}
