import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar, type SidebarGroup } from '../../components/layout/Sidebar'
import { useLayout } from '../../contexts/LayoutContext'

export function AdminLayout() {
  const { layout } = useLayout()
  const location = useLocation()
  const path = location.pathname

  document.title = 'Administration \u00b7 Glowroot'

  const groups: SidebarGroup[] = [
    {
      items: [
        { label: 'General', href: '/modern/admin/general' },
        {
          label: 'Users',
          href: '/modern/admin/user-list',
          active: path === '/modern/admin/user-list' || path === '/modern/admin/user',
        },
        {
          label: 'Roles',
          href: '/modern/admin/role-list',
          active: path === '/modern/admin/role-list' || path === '/modern/admin/role',
        },
        { label: 'Web', href: '/modern/admin/web' },
        { label: 'Storage', href: '/modern/admin/storage' },
        { label: 'SMTP', href: '/modern/admin/smtp' },
        { label: 'HTTP proxy', href: '/modern/admin/http-proxy' },
        {
          label: 'Integrations',
          href: '/modern/admin/integration-list',
          active:
            path === '/modern/admin/integration-list' ||
            path.startsWith('/modern/admin/integration/'),
        },
      ],
    },
    {
      items: [
        { label: 'admin.json', href: '/modern/admin/json' },
      ],
    },
  ]

  return (
    <div>
      <div className="mb-4">
        {layout.embeddedAgentRollup?.lastDisplayPart ? (
          <h1 className="gt-header-text text-2xl font-bold text-[#8e6c15]">
            {layout.embeddedAgentRollup.lastDisplayPart}
            <span className="mx-2 text-gray-400">|</span>
            Administration
          </h1>
        ) : (
          <h1 className="gt-header-text text-2xl font-bold text-[#8e6c15]">Administration</h1>
        )}
      </div>
      <div className="flex gap-6">
        <Sidebar groups={groups} />
        <div className="flex-1 min-w-0">
          <Outlet />
        </div>
      </div>
    </div>
  )
}
