import { Outlet, useLocation } from 'react-router-dom'
import { AgentSelector } from '../../components/layout/AgentSelector'
import { Sidebar, type SidebarGroup } from '../../components/layout/Sidebar'
import { useLayout } from '../../contexts/LayoutContext'
import { useAgent } from '../../contexts/AgentContext'

export function JvmLayout() {
  const { layout } = useLayout()
  const { agentRollup, agentRollupId, isRollup, agentQueryString } = useAgent()
  const location = useLocation()
  const path = location.pathname

  document.title = 'JVM · Glowroot'

  const needsAgent = layout.central && !agentRollupId
  const qs = agentQueryString()
  const perms = agentRollup?.permissions?.jvm

  const groups: SidebarGroup[] = [
    {
      items: [
        {
          label: 'Gauges',
          href: '/modern/jvm/gauges' + qs,
          hidden: !(perms?.gauges ?? true),
        },
        {
          label: 'Thread dump',
          href: '/modern/jvm/thread-dump' + qs,
          active: path === '/modern/jvm/thread-dump' || path === '/modern/jvm/jstack',
          hidden: !(perms?.threadDump) || isRollup || layout.offlineViewer,
        },
        {
          label: 'Heap dump',
          href: '/modern/jvm/heap-dump' + qs,
          hidden: !(perms?.heapDump) || isRollup || layout.offlineViewer,
        },
        {
          label: 'Heap histogram',
          href: '/modern/jvm/heap-histogram' + qs,
          hidden: !(perms?.heapHistogram) || isRollup || layout.offlineViewer,
        },
        {
          label: 'Force GC',
          href: '/modern/jvm/force-gc' + qs,
          hidden: !(perms?.forceGC) || isRollup || layout.offlineViewer,
        },
        {
          label: 'MBean tree',
          href: '/modern/jvm/mbean-tree' + qs,
          hidden: !(perms?.mbeanTree) || isRollup || layout.offlineViewer,
        },
        {
          label: 'System properties',
          href: '/modern/jvm/system-properties' + qs,
          hidden: !(perms?.systemProperties) || isRollup || layout.offlineViewer,
        },
        {
          label: 'Environment',
          href: '/modern/jvm/environment' + qs,
          hidden: !(perms?.environment) || isRollup,
        },
      ],
    },
  ]

  const subHeading =
    path.endsWith('/gauges')
      ? 'Gauges'
      : path.endsWith('/thread-dump')
        ? 'Thread dump'
        : path.endsWith('/jstack')
          ? 'Thread dump: jstack'
          : path.endsWith('/heap-dump')
            ? 'Heap dump'
            : path.endsWith('/heap-histogram')
              ? 'Heap histogram'
              : path.endsWith('/force-gc')
                ? 'Force GC'
                : path.endsWith('/mbean-tree')
                  ? 'MBean tree'
                  : path.endsWith('/system-properties')
                    ? 'System properties'
                    : path.endsWith('/environment')
                      ? 'Environment'
                      : path.endsWith('/capabilities')
                        ? 'Capabilities'
                        : null

  const displayName = !layout.central
    ? layout.embeddedAgentRollup?.topLevelDisplay || layout.embeddedAgentRollup?.lastDisplayPart || ''
    : ''

  return (
    <div>
      <div className="gt-header pt-5 pb-12">
        <div className="gt-header-lhs flex flex-wrap items-center gap-1">
          {layout.central ? (
            <>
              <AgentSelector />
              <div
                aria-hidden="true"
                className="gt-header-text gt-separator mx-1.5 -mt-2 select-none text-[3.25rem] font-normal leading-none text-[#785b12]"
              >
                |
              </div>
              <h1 className="gt-header-text m-0 text-[2.25rem] font-bold leading-none text-[#785b12]">JVM</h1>
            </>
          ) : displayName ? (
            <>
              <div className="gt-header-text text-[2.25rem] font-bold leading-none text-[#785b12]">
                {displayName}
              </div>
              <div
                aria-hidden="true"
                className="gt-header-text gt-separator mx-1.5 -mt-2 select-none text-[3.25rem] font-normal leading-none text-[#785b12]"
              >
                |
              </div>
              <h1 className="gt-header-text m-0 text-[2.25rem] font-bold leading-none text-[#785b12]">JVM</h1>
            </>
          ) : (
            <h1 className="gt-header-text m-0 text-[2.25rem] font-bold leading-none text-[#785b12]">JVM</h1>
          )}
        </div>
      </div>
      <div className="flex gap-6">
        <div className="print:hidden shrink-0">
          <Sidebar groups={groups} />
        </div>
        <div className="flex-1 min-w-0">
          {needsAgent ? (
            <p className="text-sm text-gray-500">Please select an agent from the dropdown above.</p>
          ) : (
            <>
              {subHeading && (
                <h2 className="mb-4 text-lg font-semibold text-gray-800">{subHeading}</h2>
              )}
              <Outlet />
            </>
          )}
        </div>
      </div>
    </div>
  )
}
