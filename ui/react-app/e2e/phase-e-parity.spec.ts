import { test, expect } from '@playwright/test'

test.describe('Phase E - Shared Shell Parity', () => {
  test('unsaved-changes guard blocks dirty navigation with Cancel and Yes actions', async ({ page }) => {
    await page.goto('/modern/admin/general')
    const displayNameInput = page.locator('main input').first()
    await expect(displayNameInput).toBeVisible()

    const originalValue = await displayNameInput.inputValue()
    const dirtyValue = originalValue === 'Dirty Test Agent' ? 'Dirty Test Agent 2' : 'Dirty Test Agent'
    await displayNameInput.fill(dirtyValue)

    await page.getByRole('link', { name: 'Users' }).click()
    await expect(page.getByText('You have unsaved changes')).toBeVisible()
    await expect(page.getByText('Are you sure you want to navigate away from this page?')).toBeVisible()

    await page.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.getByText('You have unsaved changes')).not.toBeVisible()
    await expect(page).toHaveURL(/\/modern\/admin\/general/)
    await expect(displayNameInput).toHaveValue(dirtyValue)

    await page.getByRole('link', { name: 'Users' }).click()
    await expect(page.getByText('You have unsaved changes')).toBeVisible()
    await page.getByRole('button', { name: 'Yes' }).click()
    await expect(page).toHaveURL(/\/modern\/admin\/user-list/)
  })

  test('UI delete confirmation modal replaces window.confirm and bypasses unsaved-changes guard', async ({ page }) => {
    let nativeDialogFired = false
    page.on('dialog', (dialog) => {
      nativeDialogFired = true
      void dialog.dismiss()
    })

    const roleName = `phase-e-test-${Date.now()}`
    await page.goto('/modern/admin/role?new')
    const nameInput = page.locator('main input[type="text"], main input:not([type])').first()
    await expect(nameInput).toBeVisible()
    await nameInput.fill(roleName)
    await page.getByRole('button', { name: 'Add' }).click()

    await expect(page).toHaveURL(new RegExp(`/modern/admin/role\\?name=${roleName}`))
    await expect(page.getByText('You have unsaved changes')).not.toBeVisible()

    // Make the existing role dirty, then delete to verify Delete bypasses unsaved-changes blocker
    const firstCheckbox = page.locator('main button[role="checkbox"], main input[type="checkbox"]').first()
    await firstCheckbox.click()

    await page.getByRole('button', { name: 'Delete' }).click()
    expect(nativeDialogFired).toBe(false)
    await expect(page.getByText(`Are you sure you want to delete role '${roleName}'?`)).toBeVisible()

    await page.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.getByText(`Are you sure you want to delete role '${roleName}'?`)).not.toBeVisible()
    await expect(page).toHaveURL(new RegExp(`/modern/admin/role\\?name=${roleName}`))

    await page.getByRole('button', { name: 'Delete' }).click()
    await page.getByRole('button', { name: 'Yes' }).click()

    await expect(page).toHaveURL(/\/modern\/admin\/role-list/)
    await expect(page.getByText('You have unsaved changes')).not.toBeVisible()
    await expect(page.getByText(roleName)).not.toBeVisible()
  })

  test('GtButtonGroup autofocuses first input and supports Enter-to-submit', async ({ page }) => {
    await page.goto('/modern/admin/general')
    const displayNameInput = page.locator('main input').first()
    await expect(displayNameInput).toBeVisible()
    await expect(displayNameInput).toBeFocused()

    const originalValue = await displayNameInput.inputValue()
    const tempValue = originalValue === 'Phase E Temp' ? 'Phase E Temp 2' : 'Phase E Temp'

    await displayNameInput.fill(tempValue)
    await displayNameInput.press('Enter')
    await expect(page.getByText('Saved', { exact: true })).toBeVisible()

    // Restore original value
    await displayNameInput.fill(originalValue)
    await displayNameInput.press('Enter')
    await expect(page.getByText('Saved', { exact: true })).toBeVisible()
  })

  test('HttpError collapses stack trace behind show/hide toggle', async ({ page }) => {
    await page.route('**/backend/config/transaction*', async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          message: 'Simulated E2E backend failure',
          stackTrace: 'java.lang.IllegalStateException: simulated\n\tat org.glowroot.Test.run(Test.java:42)',
        }),
      })
    })

    await page.goto('/modern/config/transaction')
    await expect(page.getByText('Simulated E2E backend failure')).toBeVisible()
    const toggleBtn = page.getByRole('button', { name: 'show stack trace' })
    await expect(toggleBtn).toBeVisible()
    await expect(page.getByText('java.lang.IllegalStateException: simulated')).not.toBeVisible()

    await toggleBtn.click()
    await expect(page.getByText('java.lang.IllegalStateException: simulated')).toBeVisible()
    await expect(page.getByRole('button', { name: 'hide stack trace' })).toBeVisible()

    await page.getByRole('button', { name: 'hide stack trace' }).click()
    await expect(page.getByText('java.lang.IllegalStateException: simulated')).not.toBeVisible()
  })

  test('ProfileLayout provides sidebar, title, print:hidden class, and dirty-navigation guard', async ({ page }) => {
    await page.goto('/modern/profile/change-password')
    await expect(page).toHaveTitle('Profile \u2014 Change password \u2014 Glowroot')
    await expect(page.getByRole('heading', { name: 'Profile', level: 1 })).toBeVisible()

    const profileSidebarLink = page.getByRole('link', { name: 'Change password' }).last()
    await expect(profileSidebarLink).toHaveAttribute('href', /\/modern\/profile\/change-password$/)

    const printHiddenSidebar = page.locator('[class*="print:hidden"]').first()
    await expect(printHiddenSidebar).toBeVisible()

    const saveBtn = page.getByRole('button', { name: 'Change password' })
    await expect(saveBtn).toBeDisabled()

    await page.locator('main input[type="password"]').first().fill('partial-password')
    await expect(saveBtn).toBeDisabled()

    await page.getByRole('link', { name: 'Transactions' }).first().click()
    await expect(page.getByText('You have unsaved changes')).toBeVisible()
    await page.getByRole('button', { name: 'Yes' }).click()
    await expect(page).toHaveURL(/\/modern\/transaction\/average/)
  })

  test('AgentSelector scopes chart queries and strips transaction-name on switch', async ({ page }) => {
    const rollupRequests: string[] = []

    await page.route('**/modern/transaction/average*', async (route, request) => {
      if (request.resourceType() === 'document') {
        const resp = await page.request.get(request.url())
        let html = await resp.text()
        html = html.replace('"central":false', '"central":true')
        await route.fulfill({ response: resp, body: html })
        return
      }
      await route.continue()
    })

    await page.route('**/backend/top-level-agent-rollups*', async (route, request) => {
      rollupRequests.push(request.url())
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { id: 'agent-one', display: 'agent-one' },
          { id: 'agent-two', display: 'agent-two' },
        ]),
      })
    })

    await page.route('**/backend/agent-rollup*', async (route, request) => {
      const url = new URL(request.url())
      const id = url.searchParams.get('agent-rollup-id') || 'agent-one'
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id,
          display: id,
          lastDisplayPart: id,
          permissions: {
            transaction: { overview: true, traces: true, queries: true, serviceCalls: true, threadProfile: true },
            error: { overview: true, traces: true },
            jvm: { gauges: true, threadDump: true, heapDump: true, heapHistogram: true, forceGc: true, mbeanTree: true, systemProperties: true, environment: true },
            syntheticMonitor: true,
            incident: true,
            config: { view: true, edit: { transaction: true, gauge: true, alert: true, syntheticMonitor: true, uiDefaults: true, plugin: true, instrumentation: true, advanced: true } },
          },
          defaultTimeZoneId: 'UTC',
        }),
      })
    })

    await page.route('**/backend/transaction/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          dataSeries: [],
          counts: {},
          mergedAggregate: { totalDurationNanos: 0, transactionCount: 0, errorCount: 0, asyncTransactions: false },
          transactions: [],
          moreAvailable: false,
        }),
      })
    })

    await page.goto(
      '/modern/transaction/average?agent-id=agent-one&transaction-type=Web&transaction-name=%2Fcheckout&from=1700000000000&to=1700003600000',
    )

    const selector = page.locator('#topLevelAgentRollupDropdown')
    await expect(selector).toBeVisible()
    await expect(selector).toContainText('agent-one')
    await expect.poll(() => rollupRequests.length).toBeGreaterThan(0)
    expect(rollupRequests[0]).toContain('from=1700000000000')
    expect(rollupRequests[0]).toContain('to=1700003600000')

    await selector.click()
    await page.getByRole('option', { name: 'agent-two' }).click()
    await expect(page).toHaveURL(/agent-id=agent-two/)
    await expect(page).toHaveURL(/transaction-type=Web/)
    await expect(page).not.toHaveURL(/transaction-name=/)
  })

  test('AgentSelector stays open with friendly message when no agents exist and keeps range selector visible', async ({ page }) => {
    await page.route('**/modern/transaction/average*', async (route, request) => {
      if (request.resourceType() === 'document') {
        const resp = await page.request.get(request.url())
        let html = await resp.text()
        html = html.replace('"central":false', '"central":true')
        await route.fulfill({ response: resp, body: html })
        return
      }
      await route.continue()
    })

    await page.route('**/backend/top-level-agent-rollups*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    })

    await page.goto('/modern/transaction/average')

    const selector = page.locator('#topLevelAgentRollupDropdown')
    await expect(selector).toBeVisible()
    await expect(selector).toContainText('<select>')
    await expect(page.locator('#gtTransactionType')).toContainText('<transaction type>')
    await expect(page.locator('#gtChartRange')).toContainText('Last 4 hours')
    await expect(page.locator('h2')).toContainText('All Transactions')

    await selector.click()
    await expect(page.getByRole('listbox', { name: 'Top-level agent rollup options' })).toBeVisible()
    await expect(page.getByText('No active agents in this time period')).toBeVisible()
    await expect(page.getByRole('textbox', { name: 'Search agents' })).toBeFocused()
  })
})
