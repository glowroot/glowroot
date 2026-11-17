import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet, apiPost, ApiError } from '../../lib/api'
import { GtFormGroup } from '../../components/shared/GtFormGroup'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'
import { GtSelectpicker, type SelectpickerOption } from '../../components/shared/GtSelectpicker'
import { Input } from '../../components/ui/input'
import { Checkbox } from '../../components/ui/checkbox'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface PermNode {
  key: string
  label: string
  children?: PermNode[]
}

function getAgentPermissions(central: boolean): PermNode[] {
  const editChildren: PermNode[] = []
  if (central) {
    editChildren.push({ key: 'agent:config:edit:general', label: 'General' })
  }
  editChildren.push(
    { key: 'agent:config:edit:transaction', label: 'Transactions' },
    { key: 'agent:config:edit:gauges', label: 'Gauges' },
    { key: 'agent:config:edit:jvm', label: 'JVM' },
  )
  if (central) {
    editChildren.push({ key: 'agent:config:edit:syntheticMonitors', label: 'Synthetic monitors' })
  }
  editChildren.push(
    { key: 'agent:config:edit:alerts', label: 'Alerts' },
    { key: 'agent:config:edit:uiDefaults', label: 'UI Defaults' },
    { key: 'agent:config:edit:plugins', label: 'Plugins' },
    { key: 'agent:config:edit:instrumentation', label: 'Instrumentation' },
    { key: 'agent:config:edit:advanced', label: 'Advanced' },
  )

  const nodes: PermNode[] = [
    {
      key: 'agent:transaction',
      label: 'Transactions',
      children: [
        { key: 'agent:transaction:overview', label: 'Overview' },
        { key: 'agent:transaction:traces', label: 'Traces' },
        { key: 'agent:transaction:queries', label: 'Queries' },
        { key: 'agent:transaction:serviceCalls', label: 'Service calls' },
        { key: 'agent:transaction:threadProfile', label: 'Thread profile' },
      ],
    },
    {
      key: 'agent:error',
      label: 'Errors',
      children: [
        { key: 'agent:error:overview', label: 'Overview' },
        { key: 'agent:error:traces', label: 'Traces' },
      ],
    },
    {
      key: 'agent:jvm',
      label: 'JVM',
      children: [
        { key: 'agent:jvm:gauges', label: 'Gauges' },
        { key: 'agent:jvm:threadDump', label: 'Thread dump' },
        { key: 'agent:jvm:heapDump', label: 'Heap dump' },
        { key: 'agent:jvm:heapHistogram', label: 'Heap histogram' },
        { key: 'agent:jvm:forceGC', label: 'Force GC' },
        { key: 'agent:jvm:mbeanTree', label: 'MBean tree' },
        { key: 'agent:jvm:systemProperties', label: 'System properties' },
        { key: 'agent:jvm:environment', label: 'Environment' },
      ],
    },
  ]
  if (central) {
    nodes.push({ key: 'agent:syntheticMonitor', label: 'Synthetic monitors' })
  }
  nodes.push(
    { key: 'agent:incident', label: 'Incidents' },
    {
      key: 'agent:config',
      label: 'Configuration',
      children: [
        {
          key: 'agent:config:view',
          label: central
            ? 'View (General, Slow traces, Thread profile, Gauges, System properties, Synthetic monitors, Alerts, UI Defaults, Plugins, Instrumentation, Advanced)'
            : 'View (Transactions, Gauges, Alerts, UI, Plugins, Instrumentation, Advanced)',
        },
        {
          key: 'agent:config:edit',
          label: 'Edit',
          children: editChildren,
        },
      ],
    },
  )
  return nodes
}

const ADMIN_PERMISSIONS: PermNode[] = [
  {
    key: 'admin',
    label: 'Administration (Users, Roles, Web, Storage, SMTP, HTTP proxy, Integrations)',
    children: [
      { key: 'admin:view', label: 'View' },
      { key: 'admin:edit', label: 'Edit' },
    ],
  },
]

interface PermissionBlock {
  agentRollupIds: string[]
  permissions: string[]
}

interface FlattenedAgentRollup {
  id: string
  display: string
  lastDisplayPart?: string
  depth?: number
}

interface RoleConfig {
  name: string
  permissions: string[]
  permissionBlocks?: PermissionBlock[]
  version?: string
}

interface RoleData {
  config: RoleConfig
  allActiveAgentRollups?: FlattenedAgentRollup[]
}

function transactionOverviewRequired(perms: string[]): boolean {
  return (
    !perms.includes('agent:transaction') &&
    !perms.includes('agent:transaction:overview') &&
    perms.some((p) =>
      [
        'agent:transaction:traces',
        'agent:transaction:queries',
        'agent:transaction:serviceCalls',
        'agent:transaction:threadProfile',
      ].includes(p),
    )
  )
}

function errorOverviewRequired(perms: string[]): boolean {
  return (
    !perms.includes('agent:error') &&
    !perms.includes('agent:error:overview') &&
    perms.includes('agent:error:traces')
  )
}

function viewConfigRequired(perms: string[]): boolean {
  return (
    !perms.includes('agent:config') &&
    !perms.includes('agent:config:view') &&
    perms.some((p) => p === 'agent:config:edit' || p.startsWith('agent:config:edit:'))
  )
}

function viewAdminRequired(perms: string[]): boolean {
  return (
    !perms.includes('admin') &&
    !perms.includes('admin:view') &&
    perms.includes('admin:edit')
  )
}

function normalizeRoleConfig(cfg: RoleConfig): RoleConfig {
  const permissionBlocks = (cfg.permissionBlocks ?? []).map((b) => ({
    agentRollupIds: [...b.agentRollupIds].sort(),
    permissions: [...b.permissions].sort(),
  }))
  return {
    ...cfg,
    permissions: [...cfg.permissions].sort(),
    ...(cfg.permissionBlocks !== undefined ? { permissionBlocks } : {}),
  }
}

function cloneRoleConfig(cfg: RoleConfig): RoleConfig {
  return {
    ...cfg,
    permissions: [...cfg.permissions],
    ...(cfg.permissionBlocks !== undefined
      ? {
          permissionBlocks: cfg.permissionBlocks.map((b) => ({
            agentRollupIds: [...b.agentRollupIds],
            permissions: [...b.permissions],
          })),
        }
      : {}),
  }
}

export function AdminRolePage() {
  const { layout } = useLayout()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const nameParam = searchParams.get('name')
  const isNew = !nameParam

  const [config, setConfig] = useState<RoleConfig>({ name: '', permissions: [] })
  const [originalConfig, setOriginalConfig] = useState<RoleConfig>({ name: '', permissions: [] })
  const [allActiveAgentRollups, setAllActiveAgentRollups] = useState<FlattenedAgentRollup[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const onNewData = useCallback(
    (resp: RoleData) => {
      const normalized = normalizeRoleConfig({
        ...resp.config,
        permissionBlocks: resp.config.permissionBlocks ?? [],
      })
      setConfig(cloneRoleConfig(normalized))
      setOriginalConfig(cloneRoleConfig(normalized))
      if (resp.allActiveAgentRollups) {
        setAllActiveAgentRollups(resp.allActiveAgentRollups)
      }
    },
    [],
  )

  const load = useCallback(async () => {
    try {
      if (isNew) {
        if (layout.central) {
          const rollups = await apiGet<FlattenedAgentRollup[]>(
            '/backend/admin/all-active-agent-rollups',
          )
          onNewData({
            config: { name: '', permissions: [], permissionBlocks: [] },
            allActiveAgentRollups: rollups,
          })
        } else {
          onNewData({
            config: { name: '', permissions: [], permissionBlocks: [] },
          })
        }
      } else if (nameParam) {
        const resp = await apiGet<RoleData>('/backend/admin/roles', { name: nameParam })
        onNewData(resp)
      }
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [isNew, nameParam, layout.central, onNewData])

  useEffect(() => { load() }, [load])

  const hasChanges = JSON.stringify(config) !== JSON.stringify(originalConfig)

  function updatePermList(current: string[], perm: string, checked: boolean): string[] {
    let perms = [...current]
    if (checked) {
      if (!perms.includes(perm)) {
        perms.push(perm)
      }
      // When parent permission is checked, remove child permissions
      perms = perms.filter((p) => !(p.startsWith(perm + ':') && p !== perm))
    } else {
      perms = perms.filter((p) => p !== perm)
    }
    return perms.sort()
  }

  function togglePermission(perm: string, checked: boolean) {
    setConfig({ ...config, permissions: updatePermList(config.permissions, perm, checked) })
  }

  function toggleBlockPermission(blockIndex: number, perm: string, checked: boolean) {
    const blocks = [...(config.permissionBlocks ?? [])]
    blocks[blockIndex] = {
      ...blocks[blockIndex],
      permissions: updatePermList(blocks[blockIndex].permissions, perm, checked),
    }
    setConfig({ ...config, permissionBlocks: blocks })
  }

  function updateBlockAgentRollups(blockIndex: number, agentRollupIds: string[]) {
    const blocks = [...(config.permissionBlocks ?? [])]
    blocks[blockIndex] = {
      ...blocks[blockIndex],
      agentRollupIds: [...agentRollupIds].sort(),
    }
    setConfig({ ...config, permissionBlocks: blocks })
  }

  function addPermissionBlock() {
    setConfig({
      ...config,
      permissionBlocks: [
        ...(config.permissionBlocks ?? []),
        { agentRollupIds: [], permissions: [] },
      ],
    })
  }

  function removePermissionBlock(blockIndex: number) {
    const blocks = [...(config.permissionBlocks ?? [])]
    blocks.splice(blockIndex, 1)
    setConfig({ ...config, permissionBlocks: blocks })
  }

  function hasParentPermission(perms: string[], perm: string): boolean {
    const parts = perm.split(':')
    for (let i = 1; i < parts.length; i++) {
      const parent = parts.slice(0, i).join(':')
      if (perms.includes(parent)) return true
    }
    return false
  }

  function validatePermissions(): boolean {
    const checkSet = (perms: string[]): boolean =>
      !transactionOverviewRequired(perms) &&
      !errorOverviewRequired(perms) &&
      !viewConfigRequired(perms) &&
      !viewAdminRequired(perms)

    if (!checkSet(config.permissions)) return false
    if (layout.central) {
      for (const block of config.permissionBlocks ?? []) {
        if (block.agentRollupIds.length === 0) return false
        if (!checkSet(block.permissions)) return false
      }
    }
    return true
  }

  async function handleSave() {
    if (!validatePermissions()) {
      throw new Error('See validation error(s) above')
    }
    try {
      if (isNew) {
        const resp = await apiPost<RoleData>('/backend/admin/roles/add', config)
        navigate(`/modern/admin/role?name=${encodeURIComponent(resp.config.name)}`, { replace: true })
        onNewData(resp)
      } else {
        const resp = await apiPost<RoleData>('/backend/admin/roles/update', config)
        onNewData(resp)
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        throw new Error('There is already a role with this name')
      }
      throw e
    }
  }

  async function handleDelete() {
    const resp = await apiPost<{ errorCannotDeleteLastRole?: boolean }>(
      '/backend/admin/roles/remove',
      { name: config.name || nameParam },
    )
    if (resp.errorCannotDeleteLastRole) {
      throw new Error('Cannot delete last role')
    }
    navigate('/modern/admin/role-list', { replace: true })
  }

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  const agentPermissionNodes = getAgentPermissions(layout.central)
  const rollupsExist = allActiveAgentRollups.some((r) => r.id.endsWith('::'))

  function getDependencyHint(perms: string[], key: string): string | null {
    if (key === 'agent:transaction:overview' && transactionOverviewRequired(perms)) {
      return '-- required for other transaction permissions'
    }
    if (key === 'agent:error:overview' && errorOverviewRequired(perms)) {
      return '-- required for other error permissions'
    }
    if (key === 'agent:config:view' && viewConfigRequired(perms)) {
      return '-- required for edit permission'
    }
    if (key === 'admin:view' && viewAdminRequired(perms)) {
      return '-- required for edit permission'
    }
    return null
  }

  function renderPermTree(
    items: PermNode[],
    perms: string[],
    onToggle: (perm: string, checked: boolean) => void,
    depth = 0,
  ) {
    return items.map((item) => {
      const parentDisabled = hasParentPermission(perms, item.key)
      const hint = getDependencyHint(perms, item.key)
      return (
        <div key={item.key} style={{ marginLeft: depth * 20 }} data-gt-invalid={hint ? 'other' : undefined}>
          <label className="flex items-center gap-2 py-0.5 text-sm">
            <Checkbox
              checked={perms.includes(item.key) || parentDisabled}
              onCheckedChange={(c) => onToggle(item.key, c)}
              disabled={!layout.adminEdit || parentDisabled}
            />
            <span className={parentDisabled ? 'text-gray-400' : ''}>
              {item.label}
              {hint && <span className="ml-2 text-red-600 italic">{hint}</span>}
            </span>
          </label>
          {item.children && renderPermTree(item.children, perms, onToggle, depth + 1)}
        </div>
      )
    })
  }

  function buildBlockRollupOptions(selectedIds: string[]): SelectpickerOption[] {
    const activeIds = new Set(allActiveAgentRollups.map((r) => r.id))
    const opts: SelectpickerOption[] = allActiveAgentRollups.map((r) => ({
      value: r.id,
      label: r.lastDisplayPart || r.display,
      indent: r.depth ?? 0,
    }))
    for (const id of selectedIds) {
      if (!activeIds.has(id)) {
        opts.push({
          value: id,
          label: `${id} (not active in the past 7 days)`,
          indent: 0,
        })
      }
    }
    return opts
  }

  const permissionBlocks = config.permissionBlocks ?? []

  return (
    <div>
      <div className="mb-3">
        <Link to="/modern/admin/role-list" className="text-sm text-blue-600 hover:underline">
          Return to list
        </Link>
      </div>
      <SettingsPage title={`Roles — ${isNew ? '<New>' : config.name}`}>

        <GtFormGroup label="Name" required value={config.name}>
          <Input
            value={config.name}
            onChange={(e) => setConfig({ ...config, name: e.target.value })}
            disabled={!isNew || !layout.adminEdit}
          />
        </GtFormGroup>

        {layout.central ? (
          <>
            <fieldset className="rounded border border-gray-200 p-4">
              <legend className="px-2 text-sm font-semibold text-gray-700">All agents</legend>
              <GtFormGroup label="Permissions">
                <div className="space-y-1 pt-1">
                  {renderPermTree(agentPermissionNodes, config.permissions, togglePermission)}
                </div>
              </GtFormGroup>
            </fieldset>

            <fieldset className="rounded border border-gray-200 p-4">
              <legend className="px-2 text-sm font-semibold text-gray-700">Server administration</legend>
              <GtFormGroup label="Permissions">
                <div className="space-y-1 pt-1">
                  {renderPermTree(ADMIN_PERMISSIONS, config.permissions, togglePermission)}
                </div>
              </GtFormGroup>
            </fieldset>

            {permissionBlocks.map((block, blockIndex) => (
              <fieldset key={blockIndex} className="rounded border border-gray-200 p-4">
                <legend className="px-2 text-sm font-semibold text-gray-700">
                  Agent specific permission block
                </legend>
                <GtFormGroup
                  label="Agents"
                  required
                  value={block.agentRollupIds.length > 0 ? 'selected' : ''}
                >
                  <GtSelectpicker
                    multiple
                    options={buildBlockRollupOptions(block.agentRollupIds)}
                    values={block.agentRollupIds}
                    onChangeMultiple={(vals) => updateBlockAgentRollups(blockIndex, vals)}
                    placeholder="Select agents..."
                    disabled={!layout.adminEdit}
                    className="w-full max-w-xl"
                  />
                  {rollupsExist && (
                    <p className="mt-1 text-xs text-gray-500 italic">
                      Note that permissions on rollups are inherited by their children.
                    </p>
                  )}
                </GtFormGroup>
                <GtFormGroup label="Permissions">
                  <div className="space-y-1 pt-1">
                    {renderPermTree(
                      agentPermissionNodes,
                      block.permissions,
                      (perm, checked) => toggleBlockPermission(blockIndex, perm, checked),
                    )}
                  </div>
                </GtFormGroup>
                {layout.adminEdit && (
                  <div className="flex justify-end mt-2">
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => removePermissionBlock(blockIndex)}
                    >
                      Remove this agent specific permission block
                    </Button>
                  </div>
                )}
              </fieldset>
            ))}

            {layout.adminEdit && (
              <fieldset className="rounded border border-gray-200 p-4">
                <legend className="px-2 text-sm font-semibold text-gray-700">
                  Add {permissionBlocks.length ? 'another' : 'an'} agent specific permission block
                </legend>
                {allActiveAgentRollups.length === 0 ? (
                  <p className="text-sm text-gray-500 italic">
                    There are no agents active in the past 7 days
                  </p>
                ) : (
                  <Button variant="secondary" size="sm" onClick={addPermissionBlock}>
                    Add
                  </Button>
                )}
              </fieldset>
            )}
          </>
        ) : (
          <GtFormGroup label="Permissions">
            <div className="space-y-1 pt-1">
              {renderPermTree(agentPermissionNodes, config.permissions, togglePermission)}
              {renderPermTree(ADMIN_PERMISSIONS, config.permissions, togglePermission)}
            </div>
          </GtFormGroup>
        )}

        {layout.adminEdit && (
          <GtButtonGroup
            onSave={handleSave}
            hasChanges={hasChanges}
            validate={validatePermissions}
            saveLabel={isNew ? 'Add' : 'Save changes'}
            onDelete={!isNew && config.version !== undefined ? handleDelete : undefined}
            showDelete={!isNew && config.version !== undefined}
            deleteConfirmHeader="Delete role"
            deleteConfirmMessage={`Are you sure you want to delete role '${config.name}'?`}
          />
        )}
      </SettingsPage>
    </div>
  )
}
