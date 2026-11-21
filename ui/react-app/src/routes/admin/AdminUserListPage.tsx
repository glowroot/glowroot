import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet } from '../../lib/api'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'
import { SettingsPage } from '../../components/shared/SettingsPage'

interface User {
  username: string
  roles: string[]
}

export function AdminUserListPage() {
  const { layout } = useLayout()
  const [users, setUsers] = useState<User[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    try {
      const resp = await apiGet<User[]>('/backend/admin/users')
      // Sort: anonymous last, then alphabetical
      resp.sort((a, b) => {
        if (a.username.toLowerCase() === 'anonymous') return 1
        if (b.username.toLowerCase() === 'anonymous') return -1
        return a.username.localeCompare(b.username)
      })
      setUsers(resp)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  if (loading) return <PageSpinner />
  if (error) return <HttpError error={error} />

  return (
    <SettingsPage title="Users" wide>
      <div className="divide-y divide-gray-100 border-t border-gray-100">
        {users.length === 0 ? (
          <div className="py-3 text-sm text-gray-500 italic">There are no users</div>
        ) : (
          users.map((user) => {
            const displayName = user.username.toLowerCase() === 'anonymous'
              ? '<anonymous>'
              : user.username
            return (
              <Link
                key={user.username}
                to={`/modern/admin/user?username=${encodeURIComponent(user.username)}`}
                className="flex items-center justify-between py-3 text-sm no-underline hover:bg-gray-50/80 -mx-1 px-1 rounded"
              >
                <span className="text-gray-900">{displayName}</span>
                <span className="text-xs text-gray-500">{user.roles.join(', ')}</span>
              </Link>
            )
          })
        )}
      </div>
      {layout.adminEdit && (
        <div className="mt-4">
          <Link
            to="/modern/admin/user?new"
            className="inline-flex items-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 no-underline"
          >
            Add new
          </Link>
        </div>
      )}
    </SettingsPage>
  )
}
