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
      items: [{ label: 'General', href: '/modern/admin/general' }],
    },
    {
      label: 'Access',
      items: [
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
      ],
    },
    {
      label: 'Server',
      items: [
        { label: 'Web', href: '/modern/admin/web' },
        { label: 'Storage', href: '/modern/admin/storage' },
      ],
    },
    {
      label: 'Delivery',
      items: [
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
      items: [{ label: 'admin.json', href: '/modern/admin/json' }],
    },
  ]

  const displayName = layout.embeddedAgentRollup?.lastDisplayPart || ''

  return (
    <div>
      <div className="gt-header pt-5 pb-12">
        <div className="gt-header-lhs flex flex-wrap items-center gap-1">
          {displayName ? (
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
              <h1 className="gt-header-text m-0 text-[2.25rem] font-bold leading-none text-[#785b12]">
                Administration
              </h1>
            </>
          ) : (
            <h1 className="gt-header-text m-0 text-[2.25rem] font-bold leading-none text-[#785b12]">
              Administration
            </h1>
          )}
        </div>
      </div>
      <div className="flex gap-8">
        <Sidebar groups={groups} />
        <div className="flex-1 min-w-0">
          <Outlet />
        </div>
      </div>
    </div>
  )
}
