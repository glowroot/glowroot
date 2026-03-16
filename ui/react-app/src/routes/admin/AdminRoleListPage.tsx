import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useLayout } from '../../contexts/LayoutContext'
import { apiGet } from '../../lib/api'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface Role {
  name: string
}

export function AdminRoleListPage() {
  const { layout } = useLayout()
  const [roles, setRoles] = useState<Role[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async () => {
    try {
      const resp = await apiGet<Role[]>('/backend/admin/roles')
      setRoles(resp)
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
    <div className="rounded-lg border bg-white p-6">
      <h2 className="text-lg font-semibold text-gray-900 mb-4">Roles</h2>
      <div className="rounded-md border">
        {roles.length === 0 ? (
          <div className="px-4 py-3 text-sm text-gray-500 italic">There are no roles</div>
        ) : (
          roles.map((role) => (
            <Link
              key={role.name}
              to={`/modern/admin/role?name=${encodeURIComponent(role.name)}`}
              className="flex items-center px-4 py-3 text-sm text-gray-900 border-b last:border-b-0 no-underline hover:bg-gray-50"
            >
              {role.name}
            </Link>
          ))
        )}
      </div>
      {layout.adminEdit && (
        <div className="mt-4">
          <Link
            to="/modern/admin/role?new"
            className="inline-flex items-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 no-underline"
          >
            Add new
          </Link>
        </div>
      )}
    </div>
  )
}
