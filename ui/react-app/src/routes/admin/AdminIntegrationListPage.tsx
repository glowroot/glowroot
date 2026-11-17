import { Link } from 'react-router-dom'
import { SettingsPage } from '../../components/shared/SettingsPage'

export function AdminIntegrationListPage() {
  const items = [
    { label: 'LDAP', href: '/modern/admin/integration/ldap' },
    { label: 'PagerDuty', href: '/modern/admin/integration/pager-duty' },
    { label: 'Slack', href: '/modern/admin/integration/slack' },
    { label: 'Healthchecks.io', href: '/modern/admin/integration/healthchecks-io' },
  ]

  return (
    <SettingsPage title="Integrations" wide>
      <div className="divide-y divide-gray-100 border-t border-gray-100">
        {items.map((item) => (
          <Link
            key={item.href}
            to={item.href}
            className="flex items-center py-3 text-sm text-gray-700 no-underline hover:bg-gray-50/80 -mx-1 px-1 rounded"
          >
            {item.label}
          </Link>
        ))}
      </div>
    </SettingsPage>
  )
}
