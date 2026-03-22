import { test, expect } from '@playwright/test'

test.describe('Phase F - Administration & Configuration Parity', () => {
  test('Admin User page shows heading, return link, sorted roles with links, password validation, and no-role confirmation dialog', async ({ page }) => {
    let addCalled = false
    await page.route('**/backend/admin/users/add', async (route) => {
      addCalled = true
      await route.continue()
    })

    await page.goto('/modern/admin/user?new', { waitUntil: 'networkidle' })

    await expect(page.getByRole('link', { name: 'Return to list' })).toHaveAttribute(
      'href',
      '/modern/admin/user-list'
    )
    await expect(page.getByRole('heading', { name: /Users — <New>/ })).toBeVisible()

    // Check role checkboxes and view/edit links
    const roleLinks = page.locator('a[href^="/modern/admin/role?name="]')
    expect(await roleLinks.count()).toBeGreaterThan(0)
    await expect(roleLinks.first()).toContainText('view/edit')

    // Fill username and mismatched passwords
    const usernameInput = page.locator('main input').first()
    await usernameInput.fill('phase-f-test-user')
    const passwordInputs = page.locator('main input[type="password"]')
    await passwordInputs.nth(0).fill('secret1')
    await passwordInputs.nth(1).fill('secret2')

    await page.getByRole('button', { name: 'Add' }).click()
    await expect(page.getByText('Passwords do not match')).toBeVisible()
    expect(addCalled).toBe(false)

    // Matching passwords with no role assignments opens React Dialog modal
    await passwordInputs.nth(1).fill('secret1')
    await page.getByRole('button', { name: 'Add' }).click()

    await expect(page.getByText('User has no role assignments')).toBeVisible()
    await page.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.getByText('User has no role assignments')).not.toBeVisible()
    await expect(page).toHaveURL(/\/modern\/admin\/user\?new/)
    expect(addCalled).toBe(false)
  })

  test('Admin Role page shows heading, return link, permission dependency hints, and Central agent permission blocks', async ({ page, browser }) => {
    await page.goto('/modern/admin/role?name=Administrator', { waitUntil: 'networkidle' })
    await expect(page.getByRole('link', { name: 'Return to list' })).toHaveAttribute(
      'href',
      '/modern/admin/role-list'
    )
    await expect(page.getByRole('heading', { name: /Roles — Administrator/ })).toBeVisible()

    // Check permission dependency hint on new role when Traces is checked without Overview
    await page.goto('/modern/admin/role?new', { waitUntil: 'networkidle' })
    const tracesCheckbox = page.getByRole('checkbox', { name: /^Traces/ }).first()
    await expect(tracesCheckbox).toBeVisible()
    await tracesCheckbox.click()
    await expect(page.getByText('-- required for other transaction permissions')).toBeVisible()

    // Mock Central mode via HTML layout script injection in a fresh browser context
    const centralCtx = await browser.newContext()
    const centralPage = await centralCtx.newPage()
    try {
      await centralPage.route('**/modern/**', async (route) => {
        if (route.request().resourceType() !== 'document') {
          await route.continue()
          return
        }
        const response = await route.fetch()
        const html = await response.text()
        const match = html.match(/<script>var layout=(\{.*?\});var contextPath=/)
        if (!match) {
          await route.fulfill({ response, body: html })
          return
        }
        const layout = JSON.parse(match[1])
        layout.central = true
        const modified = html.replace(match[1], JSON.stringify(layout))
        await route.fulfill({ response, body: modified })
      })
      await centralPage.route('**/backend/admin/all-active-agent-rollups*', async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            { id: 'agent-1', display: 'Agent 1', lastDisplayPart: 'Agent 1', depth: 0 },
            { id: 'group-1::agent-2', display: 'Agent 2', lastDisplayPart: 'Agent 2', depth: 1 },
          ]),
        })
      })
      await centralPage.route('**/backend/admin/roles*', async (route) => {
        if (route.request().method() !== 'GET') {
          await route.continue()
          return
        }
        const response = await route.fetch()
        const json = await response.json()
        if (json.config) {
          json.config.permissionBlocks = json.config.permissionBlocks || []
        }
        json.allActiveAgentRollups = [
          { id: 'agent-1', display: 'Agent 1', lastDisplayPart: 'Agent 1', depth: 0 },
          { id: 'group-1::agent-2', display: 'Agent 2', lastDisplayPart: 'Agent 2', depth: 1 },
        ]
        await route.fulfill({ response, json })
      })

      await centralPage.goto('/modern/admin/role?name=Administrator', { waitUntil: 'networkidle' })
      await expect(centralPage.getByText('All agents')).toBeVisible()
      await expect(centralPage.getByText('Server administration')).toBeVisible()
      await expect(centralPage.getByText(/Add.*agent specific permission block/i)).toBeVisible()
    } finally {
      await centralCtx.close()
    }
  })

  test('Admin Web and Storage pages show headings, OpenSSL/PEM help text, H2 cache, wiki link, and hash focus', async ({ page }) => {
    await page.goto('/modern/admin/web', { waitUntil: 'networkidle' })
    await expect(page.getByRole('heading', { name: 'Web' })).toBeVisible()
    await expect(page.getByText(/OpenSSL/i)).toBeVisible()
    await expect(page.getByText('ui-cert.pem', { exact: true })).toBeVisible()

    await page.goto('/modern/admin/storage#rollup-capped-database-size', { waitUntil: 'networkidle' })
    await expect(page.getByRole('heading', { name: 'Storage' })).toBeVisible()
    await expect(page.getByText('H2 cache')).toBeVisible()
    await expect(page.locator('#h2CacheMode')).toBeVisible()
    await expect(page.getByText(/Effective after save/i)).toBeVisible()

    const wikiLink = page.getByRole('link', { name: 'Glowroot wiki' })
    await expect(wikiLink).toHaveAttribute(
      'href',
      'https://github.com/glowroot/glowroot/wiki/Administration-Storage'
    )

    const rollupInput = page.locator('#rollup-capped-database-size')
    await expect(rollupInput).toBeVisible()
    await expect(rollupInput).toBeFocused()
  })

  test('Config Gauge, UI Defaults, Instrumentation, and Instrumentation List parity', async ({ page }) => {
    // 1. Gauge page
    await page.goto('/modern/config/gauge?new', { waitUntil: 'networkidle' })
    await expect(page.getByRole('link', { name: 'Return to list' })).toHaveAttribute(
      'href',
      '/modern/config/gauge-list'
    )
    await expect(page.getByRole('heading', { name: /Gauges — <New>/ })).toBeVisible()
    await expect(page.getByText('(select MBean object name first)')).toBeVisible()

    // 2. UI Defaults page + percentile pattern validation
    let uiDefaultsPosted = false
    await page.route('**/backend/config/ui-defaults*', async (route) => {
      if (route.request().method() === 'POST') {
        uiDefaultsPosted = true
        await route.abort()
        return
      }
      await route.continue()
    })

    await page.goto('/modern/config/ui-defaults', { waitUntil: 'networkidle' })
    await expect(page.getByRole('heading', { name: 'UI Defaults' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'HdrHistogram' })).toHaveAttribute(
      'href',
      'http://hdrhistogram.github.io/HdrHistogram/'
    )

    const percentilesInput = page.locator('main input[type="text"], main input:not([type])').first()
    await percentilesInput.fill('abc')
    await page.getByRole('button', { name: 'Save changes' }).click()
    await expect(page.getByText('This is invalid.')).toBeVisible()
    expect(uiDefaultsPosted).toBe(false)

    // 3. Instrumentation page: Timer name validation + methodReturnType/methodModifiers preservation
    let addPayload: Record<string, unknown> | null = null
    await page.route('**/backend/config/method-signatures*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            name: 'execute',
            parameterTypes: ['java.lang.String'],
            returnType: 'java.lang.String',
            modifiers: ['public'],
          },
        ]),
      })
    })
    await page.route('**/backend/config/instrumentation/add*', async (route) => {
      addPayload = JSON.parse(route.request().postData() || '{}')
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          config: { ...addPayload, version: 'mock-version-1' },
        }),
      })
    })

    await page.goto('/modern/config/instrumentation?new', { waitUntil: 'networkidle' })
    await expect(page.getByRole('link', { name: 'Return to list' })).toHaveAttribute(
      'href',
      '/modern/config/instrumentation-list'
    )
    await expect(page.getByRole('heading', { name: /Instrumentation — <New>/ })).toBeVisible()

    // Switch capture kind to Timer so fewer fields are needed and test Timer name validation
    const captureKindGroup = page.locator('label:has-text("Capture kind")').locator('..')
    await captureKindGroup.locator('select').selectOption('timer')
    await page.getByPlaceholder('e.g. com.example.MyClass').fill('com.example.Worker')
    const methodInput = page.getByPlaceholder('e.g. execute')
    await methodInput.fill('execute')
    await methodInput.blur()

    // Select the mocked signature with public + java.lang.String return type
    const sigGroup = page.locator('label:has-text("Method signature")').locator('..')
    const sigSelect = sigGroup.locator('select')
    await expect(sigSelect).toContainText('public java.lang.String execute(java.lang.String)')
    await sigSelect.selectOption('1')

    // Fill invalid Timer name first
    const timerGroup = page.locator('label:has-text("Timer name")').locator('..')
    await timerGroup.locator('input').fill('bad_timer!')
    await page.getByRole('button', { name: 'Add' }).click()
    await expect(
      page.getByText('This is not a valid timer name, only letters, numbers and spaces are allowed')
    ).toBeVisible()
    expect(addPayload).toBeNull()

    // Now fix Timer name to valid letters + space and submit
    await timerGroup.locator('input').fill('valid timer')
    await page.getByRole('button', { name: 'Add' }).click()
    await expect.poll(() => addPayload).not.toBeNull()
    expect(addPayload?.methodReturnType).toBe('java.lang.String')
    expect(addPayload?.methodModifiers).toEqual(['public'])

    // 4. Instrumentation List page: Delete all instrumentation Dialog modal
    let nativeDialogFired = false
    page.on('dialog', (dialog) => {
      nativeDialogFired = true
      void dialog.dismiss()
    })
    await page.route('**/backend/config/instrumentation?*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          configs: [
            {
              className: 'com.example.Worker',
              methodName: 'execute',
              captureKind: 'timer',
              version: 'v-mock-1',
            },
          ],
          jvmOutOfSync: false,
          jvmRetransformClassesSupported: true,
        }),
      })
    })

    await page.goto('/modern/config/instrumentation-list', { waitUntil: 'networkidle' })
    await expect(page.getByRole('heading', { name: 'Instrumentation' })).toBeVisible()
    await page.getByRole('button', { name: 'Delete all' }).click()
    await expect(page.getByRole('heading', { name: 'Delete all instrumentation' })).toBeVisible()
    await expect(page.getByText('Are you sure you want to delete all instrumentation?')).toBeVisible()
    await page.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.getByRole('heading', { name: 'Delete all instrumentation' })).not.toBeVisible()
    expect(nativeDialogFired).toBe(false)
  })
})
