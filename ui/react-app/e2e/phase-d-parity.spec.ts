import { test, expect } from '@playwright/test'

const VOLATILE_PARAMS = new Set(['from', 'to', 'auto-refresh', '_'])

function recordBackendGets(page: import('@playwright/test').Page) {
  const calls = new Map<string, Set<string>>()
  const orderedParams = new Map<string, string[][]>()
  const listener = (req: import('@playwright/test').Request) => {
    if (req.method() !== 'GET') return
    const url = new URL(req.url())
    if (!url.pathname.startsWith('/backend/')) return
    if (url.pathname === '/backend/check-layout' || url.pathname === '/backend/layout') return
    const keys = [...url.searchParams.keys()]
    const normalizedKeys = keys.map((k) => (VOLATILE_PARAMS.has(k) ? k : k)).sort().join(',')
    const existing = calls.get(url.pathname) ?? new Set<string>()
    existing.add(normalizedKeys)
    calls.set(url.pathname, existing)
    const list = orderedParams.get(url.pathname) ?? []
    list.push(keys)
    orderedParams.set(url.pathname, list)
  }
  page.on('request', listener)
  return {
    calls,
    orderedParams,
    stop: () => page.off('request', listener),
  }
}

async function patchModernLayout(
  page: import('@playwright/test').Page,
  overrides: Record<string, unknown>
) {
  await page.route('**/modern/**', async (route) => {
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
    Object.assign(layout, overrides)
    const modified = html.replace(match[1], JSON.stringify(layout))
    await route.fulfill({ response, body: modified })
  })
}

test.describe('Phase D full parity & edge cases', () => {
  test('Group 1 (Cross-UI HTTP): side-by-side classic vs modern requests and agent-rollup-id key order match', async ({ browser }) => {
    const routes = [
      '/transaction/average?transaction-type=Sandbox',
      '/transaction/traces?transaction-type=Sandbox',
      '/transaction/queries?transaction-type=Sandbox',
      '/jvm/gauges',
      '/incidents',
      '/report/ad-hoc',
      '/config/transaction',
      '/admin/general',
    ]

    const classicCtx = await browser.newContext()
    const modernCtx = await browser.newContext()
    const classicPage = await classicCtx.newPage()
    const modernPage = await modernCtx.newPage()

    try {
      for (const route of routes) {
        const classicRec = recordBackendGets(classicPage)
        const modernRec = recordBackendGets(modernPage)

        await classicPage.goto(route, { waitUntil: 'networkidle' })
        await modernPage.goto(`/modern${route}`, { waitUntil: 'networkidle' })
        await classicPage.waitForTimeout(300)
        await modernPage.waitForTimeout(300)

        classicRec.stop()
        modernRec.stop()

        const classicEndpoints = [...classicRec.calls.keys()].sort()
        const modernEndpoints = [...modernRec.calls.keys()].sort()
        expect(modernEndpoints, `Endpoint parity mismatch on ${route}`).toEqual(classicEndpoints)

        for (const endpoint of classicEndpoints) {
          const classicKeys = [...(classicRec.calls.get(endpoint) ?? [])].sort()
          const modernKeys = [...(modernRec.calls.get(endpoint) ?? [])].sort()
          expect(modernKeys, `Query key parity mismatch on ${route} (${endpoint})`).toEqual(classicKeys)
        }

        if (route.startsWith('/transaction/traces')) {
          const pointsKeysList = modernRec.orderedParams.get('/backend/transaction/points') ?? []
          expect(pointsKeysList.length).toBeGreaterThan(0)
          const lastCallKeys = pointsKeysList[pointsKeysList.length - 1]
          expect(lastCallKeys[lastCallKeys.length - 1]).toBe('agent-rollup-id')
        }
      }
    } finally {
      await classicCtx.close()
      await modernCtx.close()
    }
  })

  test('Group 2 (SHL): change password validation/submission and 412/403 save errors', async ({ page }) => {
    let postCount = 0
    let returnIncorrect = true
    await page.route('**/backend/change-password', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      postCount++
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(returnIncorrect ? { currentPasswordIncorrect: true } : {}),
      })
    })

    await page.goto('/modern/profile/change-password', { waitUntil: 'networkidle' })

    const changeBtn = page.getByRole('button', { name: 'Change password' })
    await expect(changeBtn).toBeDisabled()

    await page.locator('#currentPassword').fill('oldpass')
    await page.locator('#newPassword').fill('newpass1')
    await expect(changeBtn).toBeDisabled()

    await page.locator('#verifyNewPassword').fill('mismatch')
    await expect(changeBtn).toBeEnabled()

    await changeBtn.click()
    await expect(page.getByText('Passwords do not match')).toBeVisible()
    expect(postCount).toBe(0)

    await page.locator('#verifyNewPassword').fill('newpass1')
    await changeBtn.click()
    await expect(page.getByText('Current password is incorrect')).toBeVisible()
    expect(postCount).toBe(1)

    returnIncorrect = false
    await changeBtn.click()
    await expect(page.getByText('Password has been changed')).toBeVisible()
    expect(postCount).toBe(2)

    await page.unroute('**/backend/change-password')

    let nextStatus = 412
    await page.route('**/backend/admin/general', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      await route.fulfill({
        status: nextStatus,
        contentType: 'application/json',
        body: JSON.stringify({}),
      })
    })

    await page.goto('/modern/admin/general', { waitUntil: 'networkidle' })
    const nameInput = page.locator('main input').first()
    await nameInput.fill('Sandbox Error Test')

    const saveBtn = page.getByRole('button', { name: 'Save changes' })
    await saveBtn.click()
    await expect(
      page.getByText(
        'Someone else has updated the configuration on this page since you loaded it. Please reload the page and try again.'
      )
    ).toBeVisible()

    nextStatus = 403
    await saveBtn.click()
    await expect(page.getByText('You do not have permission to perform this action')).toBeVisible()
  })

  test('Group 3 (TXN): aria-sort, URL-synced #queryModal formatting toggle, and 2D xory brush', async ({ page }) => {
    await page.goto('/modern/transaction/queries?transaction-type=Sandbox', { waitUntil: 'networkidle' })
    await expect(page.locator('table th[aria-sort]')).toHaveCount(1)
    for (const label of ['Count', 'Time/exec', 'Rows/exec', 'Total time']) {
      const th = page.getByRole('columnheader', { name: new RegExp(`^${label}`) })
      await th.click()
      const firstDir = await th.getAttribute('aria-sort')
      expect(firstDir === 'ascending' || firstDir === 'descending').toBe(true)
      await expect(page.locator('table th[aria-sort]')).toHaveCount(1)
      await th.click()
      const secondDir = await th.getAttribute('aria-sort')
      expect(secondDir).not.toBe(firstDir)
    }

    await page.goto('/modern/transaction/service-calls?transaction-type=Sandbox', { waitUntil: 'networkidle' })
    await expect(page.locator('table th[aria-sort]')).toHaveCount(1)
    for (const label of ['Count', 'Time/exec', 'Total time']) {
      const th = page.getByRole('columnheader', { name: new RegExp(`^${label}`) })
      await th.click()
      const firstDir = await th.getAttribute('aria-sort')
      expect(firstDir === 'ascending' || firstDir === 'descending').toBe(true)
      await expect(page.locator('table th[aria-sort]')).toHaveCount(1)
      await th.click()
      const secondDir = await th.getAttribute('aria-sort')
      expect(secondDir).not.toBe(firstDir)
    }

    await page.goto(
      '/modern/transaction/queries?transaction-type=Sandbox&modal-query-type=SQL&modal-query-text=SELECT%20a%20FROM%20t%20WHERE%20id%20%3D%201',
      { waitUntil: 'networkidle' }
    )
    const queryModal = page.locator('#queryModal')
    await expect(queryModal).toBeVisible()
    await expect(page.locator('#formattedQuery')).toBeVisible()
    const toggle = page.locator('#formattingToggle')
    await expect(toggle).toHaveText('(show unformatted)')

    await toggle.click()
    await expect(page.locator('#unformattedQuery')).toBeVisible()
    await expect(page.locator('#unformattedQuery')).toContainText('SELECT a FROM t WHERE id = 1')
    await expect(toggle).toHaveText('(show formatted)')

    await page.keyboard.press('Escape')
    await expect(queryModal).toBeHidden()
    await expect.poll(() => new URL(page.url()).searchParams.has('modal-query-type')).toBe(false)
    await expect.poll(() => new URL(page.url()).searchParams.has('modal-query-text')).toBe(false)

    await page.goto('/modern/transaction/traces?transaction-type=Sandbox', { waitUntil: 'networkidle' })
    const brushOverlay = page.locator('svg g.brush rect.overlay')
    await expect(brushOverlay).toBeVisible()
    const box = await brushOverlay.boundingBox()
    expect(box).not.toBeNull()
    if (!box) throw new Error('Expected non-null bounding box on svg g.brush rect.overlay')

    const startX = box.x + box.width * 0.25
    const startY = box.y + box.height * 0.25
    const endX = box.x + box.width * 0.75
    const endY = box.y + box.height * 0.75

    await page.mouse.move(startX, startY)
    await page.mouse.down()
    await page.mouse.move(endX, endY, { steps: 10 })
    await page.mouse.up()

    await expect
      .poll(() => {
        const params = new URL(page.url()).searchParams
        return (
          params.has('from') &&
          params.has('to') &&
          params.has('duration-millis-low') &&
          params.has('duration-millis-high')
        )
      })
      .toBe(true)
  })

  test('Group 4 (CFG/ADM): alerting disable/re-enable, storage analysis confirms, and PagerDuty/Slack duplicates', async ({ page }) => {
    await page.goto('/modern/config/alert-list', { waitUntil: 'networkidle' })
    const reEnableBtn = page.getByRole('button', { name: 'Re-enable alerting' })
    if (await reEnableBtn.isVisible().catch(() => false)) {
      await reEnableBtn.click()
      await expect(reEnableBtn).toBeHidden()
    }

    await page.getByRole('button', { name: 'Temporarily disable alerting' }).click()
    await page.locator('input[type="number"]').fill('5')
    await page.getByRole('button', { name: 'Ok' }).click()
    await expect(page.getByText(/Alerting is disabled for the next/)).toBeVisible()
    await expect(reEnableBtn).toBeVisible()

    await reEnableBtn.click()
    await expect(page.getByText(/Alerting is disabled for the next/)).toBeHidden()
    await expect(page.getByRole('button', { name: 'Temporarily disable alerting' })).toBeVisible()

    const dialogHandler = (dialog: import('@playwright/test').Dialog) => dialog.accept()
    page.on('dialog', dialogHandler)
    try {
      await page.goto('/modern/admin/storage', { waitUntil: 'networkidle' })
      const h2Req = page.waitForResponse((r) => new URL(r.url()).pathname === '/backend/admin/analyze-h2-disk-space' && r.status() === 200)
      await page.getByRole('button', { name: 'Analyze H2 disk space' }).click()
      await h2Req
      await expect(page.getByText('H2 data file size:')).toBeVisible()
      await expect(page.getByText(/Live \(referenced\):/)).toBeVisible()

      const tcReq = page.waitForResponse((r) => new URL(r.url()).pathname === '/backend/admin/analyze-trace-counts' && r.status() === 200)
      await page.getByRole('button', { name: 'Analyze trace counts' }).click()
      await tcReq
      await expect(page.getByRole('columnheader', { name: 'Traces captured', exact: true }).first()).toBeVisible()
    } finally {
      page.off('dialog', dialogHandler)
    }

    await page.route('**/backend/admin/pager-duty', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ duplicateIntegrationKey: true }),
      })
    })

    await page.goto('/modern/admin/integration/pager-duty', { waitUntil: 'networkidle' })
    await page.getByRole('button', { name: /Add integration key/ }).click()
    const pdRow = page.locator('main .bg-gray-50').last()
    await pdRow.locator('input').nth(0).fill('dup-key-123')
    await pdRow.locator('input').nth(1).fill('Dup Key Display')
    await page.getByRole('button', { name: 'Save changes' }).click()
    await expect(page.getByText('Duplicate integration key found')).toBeVisible()
    await expect(pdRow.locator('input').nth(0)).toHaveValue('dup-key-123')

    await page.route('**/backend/admin/slack', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ duplicateWebhookUrl: true }),
      })
    })

    await page.goto('/modern/admin/integration/slack', { waitUntil: 'networkidle' })
    await page.getByRole('button', { name: /Add webhook/ }).click()
    const slackRow = page.locator('main .bg-gray-50').last()
    await slackRow.locator('input').nth(0).fill('https://hooks.slack.com/services/T00/B00/XXX')
    await slackRow.locator('input').nth(1).fill('#alerts')
    await page.getByRole('button', { name: 'Save changes' }).click()
    await expect(page.getByText('Duplicate webhook url found')).toBeVisible()
    await expect(slackRow.locator('input').nth(0)).toHaveValue('https://hooks.slack.com/services/T00/B00/XXX')
  })

  test('Group 5 (Multi-Mode): central agent rollup hierarchy and offlineViewer JVM sidebar gating', async ({ browser }) => {
    const centralCtx = await browser.newContext()
    const centralPage = await centralCtx.newPage()
    try {
      await patchModernLayout(centralPage, {
        central: true,
        offlineViewer: false,
        adminView: true,
        adminEdit: true,
        embeddedAgentRollup: null,
      })

      await centralPage.route('**/backend/top-level-agent-rollups**', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            { id: 'grp::', display: 'Group', disabled: false },
            { id: 'standalone', display: 'Standalone', disabled: false },
          ]),
        })
      )

      await centralPage.route('**/backend/child-agent-rollups**', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            { id: 'grp::node1', display: 'Node 1', lastDisplayPart: 'Node 1', disabled: false, depth: 0 },
            { id: 'grp::node2', display: 'Node 2', lastDisplayPart: 'Node 2', disabled: false, depth: 0 },
          ]),
        })
      )

      await centralPage.route('**/backend/agent-rollup-layout**', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 'grp::node1',
            topLevelId: 'grp::',
            topLevelDisplay: 'Group',
            childDisplay: 'Node 1',
            lastDisplayPart: 'Node 1',
            glowrootVersion: '0.14.0',
            permissions: {
              transaction: {
                overview: true,
                traces: true,
                queries: true,
                serviceCalls: true,
                threadProfile: true,
              },
              error: { overview: true, traces: true },
              jvm: {
                gauges: true,
                threadDump: true,
                heapDump: true,
                heapHistogram: true,
                forceGC: true,
                mbeanTree: true,
                systemProperties: true,
                environment: true,
                capabilities: true,
              },
              syntheticMonitor: true,
              incident: true,
              config: {
                view: true,
                edit: {
                  general: true,
                  transaction: true,
                  gauges: true,
                  jvm: true,
                  syntheticMonitors: true,
                  alerts: true,
                  uiDefaults: true,
                  plugins: true,
                  instrumentation: true,
                  advanced: true,
                  all: true,
                },
              },
            },
            transactionTypes: ['Sandbox'],
            traceAttributeNames: {},
            defaultTransactionType: 'Sandbox',
            defaultPercentiles: [50, 95, 99],
            defaultGaugeNames: ['java.lang:type=Memory:HeapMemoryUsage.used'],
            version: 'central-test-1',
          }),
        })
      )

      await centralPage.route('**/backend/transaction/**', async (route) => {
        const u = new URL(route.request().url())
        u.searchParams.delete('agent-rollup-id')
        await route.continue({ url: u.toString() })
      })

      await centralPage.goto('/modern/transaction/average?agent-id=grp%3A%3Anode1', { waitUntil: 'networkidle' })
      const topSelect = centralPage.locator('#topLevelAgentRollupDropdown')
      const childSelect = centralPage.locator('#childAgentRollupDropdown')
      await expect(topSelect).toBeVisible()
      await expect(topSelect).toContainText('Group')
      await expect(childSelect).toBeVisible()
      await expect(childSelect).toContainText('Node 1')
    } finally {
      await centralCtx.close()
    }

    const offlineCtx = await browser.newContext()
    const offlinePage = await offlineCtx.newPage()
    try {
      await patchModernLayout(offlinePage, {
        central: false,
        offlineViewer: true,
        embeddedAgentRollup: {
          id: '',
          topLevelId: '',
          topLevelDisplay: '',
          childDisplay: '',
          lastDisplayPart: '',
          glowrootVersion: '0.14.0',
          permissions: {
            transaction: {
              overview: true,
              traces: true,
              queries: true,
              serviceCalls: true,
              threadProfile: true,
            },
            error: { overview: true, traces: true },
            jvm: {
              gauges: true,
              threadDump: true,
              heapDump: true,
              heapHistogram: true,
              forceGC: true,
              mbeanTree: true,
              systemProperties: true,
              environment: true,
              capabilities: true,
            },
            syntheticMonitor: true,
            incident: true,
            config: {
              view: true,
              edit: {
                general: true,
                transaction: true,
                gauges: true,
                jvm: true,
                syntheticMonitors: true,
                alerts: true,
                uiDefaults: true,
                plugins: true,
                instrumentation: true,
                advanced: true,
                all: true,
              },
            },
          },
          transactionTypes: ['Sandbox'],
          traceAttributeNames: {},
          defaultTransactionType: 'Sandbox',
          defaultPercentiles: [50, 95, 99],
          defaultGaugeNames: ['java.lang:type=Memory:HeapMemoryUsage.used'],
          version: 'offline-test-1',
        },
      })

      await offlinePage.goto('/modern/jvm/gauges', { waitUntil: 'networkidle' })
      const sidebar = offlinePage.locator('main').locator('..')
      await expect(sidebar.getByRole('link', { name: 'Gauges' })).toBeVisible()
      await expect(sidebar.getByRole('link', { name: 'Environment' })).toBeVisible()
      await expect(sidebar.getByRole('link', { name: 'Thread dump' })).toHaveCount(0)
      await expect(sidebar.getByRole('link', { name: 'Heap dump' })).toHaveCount(0)
      await expect(sidebar.getByRole('link', { name: 'Heap histogram' })).toHaveCount(0)
      await expect(sidebar.getByRole('link', { name: 'Force GC' })).toHaveCount(0)
      await expect(sidebar.getByRole('link', { name: 'MBean tree' })).toHaveCount(0)
      await expect(sidebar.getByRole('link', { name: 'System properties' })).toHaveCount(0)
    } finally {
      await offlineCtx.close()
    }
  })
})
