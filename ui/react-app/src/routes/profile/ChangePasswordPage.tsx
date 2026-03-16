import { useState } from 'react'
import { apiPost } from '../../lib/api'
import { useLayout } from '../../contexts/LayoutContext'
import { Input } from '../../components/ui/input'
import { GtButtonGroup } from '../../components/shared/GtButtonGroup'

export function ChangePasswordPage() {
  const { layout } = useLayout()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [verifyNewPassword, setVerifyNewPassword] = useState('')

  const hasChanges = Boolean(currentPassword || newPassword || verifyNewPassword)
  const saveDisabled = !(currentPassword && newPassword && verifyNewPassword)

  async function handleSave() {
    if (newPassword !== verifyNewPassword) {
      throw new Error('Passwords do not match')
    }
    const resp = await apiPost<{ currentPasswordIncorrect?: boolean }>('/backend/change-password', {
      currentPassword,
      newPassword,
    })
    if (resp.currentPasswordIncorrect) {
      throw new Error('Current password is incorrect')
    }
    setCurrentPassword('')
    setNewPassword('')
    setVerifyNewPassword('')
  }

  return (
    <div className="mx-auto max-w-lg">
      <h1 className="text-xl font-semibold text-gray-900 mb-4">
        Change {layout.adminView ? 'my ' : ''}password
      </h1>
      <div className="rounded-lg border bg-white p-6 space-y-4">
        <div>
          <label htmlFor="currentPassword" className="block text-sm font-medium text-gray-700 mb-1">
            Current password
          </label>
          <Input
            id="currentPassword"
            type="password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor="newPassword" className="block text-sm font-medium text-gray-700 mb-1">
            New password
          </label>
          <Input
            id="newPassword"
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor="verifyNewPassword" className="block text-sm font-medium text-gray-700 mb-1">
            Verify new password
          </label>
          <Input
            id="verifyNewPassword"
            type="password"
            value={verifyNewPassword}
            onChange={(e) => setVerifyNewPassword(e.target.value)}
          />
        </div>
        <GtButtonGroup
          onSave={handleSave}
          hasChanges={hasChanges}
          saveDisabled={saveDisabled}
          saveLabel="Change password"
          saveSuccessText="Password has been changed"
        />
      </div>
    </div>
  )
}
