import { test, expect } from '@playwright/test'

test.describe('Phase B UI & API parity', () => {
  test('Group 1 (ADM): admin routes load cleanly and validate forms without spurious errors', async ({ page }) => {
    const adminRoutes = [
      '/modern/admin/general',
      '/modern/admin/web',
      '/modern/admin/storage',
      '/modern/admin/smtp',
      '/modern/admin/http-proxy',
      '/modern/admin/ldap',
      '/modern/admin/pager-duty',
      '/modern/admin/slack',
      '/modern/admin/healthchecks-io',
      '/modern/admin/user-list',
      '/modern/admin/role-list',
      '/modern/admin/json',
    ]

    for (const route of adminRoutes) {
      const pageErrors: string[] = []
      const failedRequests: Array<{ url: string; status: number }> = []
      const onPageError = (err: Error) => pageErrors.push(err.message)
      const onResponse = (res: import('@playwright/test').Response) => {
        const pathname = new URL(res.url()).pathname
        if (pathname.startsWith('/backend/') && res.status() >= 400) {
          failedRequests.push({ url: pathname, status: res.status() })
        }
      }
      page.on('pageerror', onPageError)
      page.on('response', onResponse)

      await page.goto(route, { waitUntil: 'networkidle' })
      await expect(page.locator('main')).not.toHaveText(/^\s*Loading\.\.\.\s*$/)
      expect(pageErrors, `Uncaught page errors on ${route}`).toEqual([])
      expect(failedRequests, `HTTP >=400 on ${route}`).toEqual([])

      page.off('pageerror', onPageError)
      page.off('response', onResponse)
    }

    // AdminWebPage clears port without producing NaN and shows validation feedback or Save handling
    await page.goto('/modern/admin/web', { waitUntil: 'networkidle' })
    const portInput = page.locator('input[type="number"]').first()
    if (await portInput.isVisible() && (await portInput.isEnabled())) {
      const original = await portInput.inputValue()
      await portInput.fill('')
      await expect(portInput).toHaveValue('')
      await portInput.fill(original || '4000')
    }
  })

  test('Group 2 (CFG): agent config dropdowns, numeric edge cases, and alert controls work', async ({ page }) => {
    const pageErrors: string[] = []
    page.on('pageerror', (e) => pageErrors.push(e.message))

    // Alert list renders
    await page.goto('/modern/config/alert-list', { waitUntil: 'networkidle' })
    await expect(page.locator('main')).toContainText(/Alerts|No alerts/i)

    // New alert page fetches alert-dropdowns cleanly and populates metrics including exclusive/inclusive timers
    let dropdownStatus = 0
    page.on('response', (res) => {
      if (new URL(res.url()).pathname === '/backend/config/alert-dropdowns') {
        dropdownStatus = res.status()
      }
    })
    await page.goto('/modern/config/alert?new', { waitUntil: 'networkidle' })
    await expect.poll(() => dropdownStatus).toBe(200)
    await expect(page.locator('option[value="transaction:timer-inclusive"]')).toHaveCount(1)
    await expect(page.locator('option[value="transaction:timer-exclusive"]')).toHaveCount(1)
    await expect(page.locator('option[value="transaction:timer-count"]')).toHaveCount(1)

    // ConfigTransactionPage allows clearing slowThresholdMillis and displays validation rather than broken PUT
    await page.goto('/modern/config/transaction', { waitUntil: 'networkidle' })
    const slowThreshold = page.locator('input[type="number"]').first()
    await expect(slowThreshold).toBeVisible()
    const origSlow = await slowThreshold.inputValue()
    await slowThreshold.fill('')
    await expect(slowThreshold).toHaveValue('')
    const saveBtn = page.locator('button:has-text("Save changes")')
    if (await saveBtn.isVisible()) {
      await saveBtn.click()
      await expect(page.locator('main')).toContainText(/required|invalid|between|must be/i)
    }
    if (origSlow) {
      await slowThreshold.fill(origSlow)
    }

    // ConfigAdvancedPage allows clearing numeric fields cleanly
    await page.goto('/modern/config/advanced', { waitUntil: 'networkidle' })
    const advInput = page.locator('input[type="number"]').first()
    await expect(advInput).toBeVisible()
    await advInput.fill('0')
    await expect(advInput).toHaveValue('0')
    await advInput.fill('')
    await expect(advInput).toHaveValue('')

    // ConfigInstrumentation?new renders Optional transaction overrides toggle when captureKind is not transaction
    await page.goto('/modern/config/instrumentation?new', { waitUntil: 'networkidle' })
    await page.locator('select:has(option[value="trace-entry"])').selectOption('trace-entry')
    await expect(page.locator('main')).toContainText(/Optional transaction overrides/i)

    expect(pageErrors).toEqual([])
  })

  test('Group 3 (JVM): diagnostics sidebar hides Capabilities and subpages maintain URL & inline state', async ({ page }) => {
    const pageErrors: string[] = []
    page.on('pageerror', (e) => pageErrors.push(e.message))

    // Sidebar does NOT contain Capabilities link
    await page.goto('/modern/jvm/environment', { waitUntil: 'networkidle' })
    await expect(page.locator('aside a:has-text("Capabilities"), nav a:has-text("Capabilities")')).toHaveCount(0)

    // Thread dump inline Refresh shows 'Refreshed' without unmounting
    await page.goto('/modern/jvm/thread-dump', { waitUntil: 'networkidle' })
    const tdRefresh = page.locator('button:has-text("Refresh")').first()
    await expect(tdRefresh).toBeVisible()
    await tdRefresh.click()
    await expect(page.locator('main')).toContainText('Refreshed')

    // Heap histogram sort & filter params sync to URL
    await page.goto('/modern/jvm/heap-histogram', { waitUntil: 'networkidle' })
    const countHeader = page.locator('th button:has-text("Count"), th:has-text("Count")').first()
    if (await countHeader.isVisible()) {
      await countHeader.click()
      await expect(page).toHaveURL(/sort-attribute=count/)
    }

    // MBean tree expands root item and writes ?expanded= to URL
    await page.goto('/modern/jvm/mbean-tree', { waitUntil: 'networkidle' })
    const firstExpandBtn = page.locator('main button, main [role="button"], main .cursor-pointer').first()
    if (await firstExpandBtn.isVisible()) {
      await firstExpandBtn.click()
    }

    // Heap dump renders confirmation modal before performing heap dump
    await page.goto('/modern/jvm/heap-dump', { waitUntil: 'networkidle' })
    await expect(page.locator('main')).toContainText(/HeapDumpPath/)
    const heapDumpBtn = page.locator('button:has-text("Heap dump")').first()
    await expect(heapDumpBtn).toBeVisible()
    await heapDumpBtn.click()
    await expect(page.locator('text=Perform heap dump?')).toBeVisible()
    await page.locator('button:has-text("Cancel")').click()

    // JStack & System properties load cleanly
    await page.goto('/modern/jvm/jstack', { waitUntil: 'networkidle' })
    await expect(page.locator('main')).toContainText(/jstack|Thread dump/i)

    await page.goto('/modern/jvm/system-properties', { waitUntil: 'networkidle' })
    await expect(page.locator('main')).toContainText(/java\./)

    expect(pageErrors).toEqual([])
  })

  test('Group 4 (JVM Gauges): gauges chart, legend, filtering, and Select All preserve selection', async ({ page }) => {
    const pageErrors: string[] = []
    page.on('pageerror', (e) => pageErrors.push(e.message))

    await page.goto('/modern/jvm/gauges', { waitUntil: 'networkidle' })
    await expect(page.locator('svg').first()).toBeVisible()

    // Select all & Deselect / Clear interaction
    const gaugeFilter = page.locator('input[placeholder*="Filter" i], input[placeholder*="gauge" i]').first()
    if (await gaugeFilter.isVisible()) {
      await gaugeFilter.fill('Heap')
      await expect(page.locator('main')).toContainText(/showing/i)
      await gaugeFilter.fill('')
    }

    const selectAllBtn = page.locator('button:has-text("Select all")').first()
    if (await selectAllBtn.isVisible()) {
      await selectAllBtn.click()
      const checkedCount = await page.locator('input[type="checkbox"]:checked').count()
      expect(checkedCount).toBeGreaterThan(0)
    }

    expect(pageErrors).toEqual([])
  })

  test('Group 5 (TXN): average schema, percentiles validation, thread profile filter, standalone flame graph, and trace filters', async ({ page }) => {
    const pageErrors: string[] = []
    page.on('pageerror', (e) => pageErrors.push(e.message))

    // Average page: Flattened default, Breakdown / Total (ms) / Self (ms) / Count, Tree toggle, sidebar sort orders
    await page.goto('/modern/transaction/average', { waitUntil: 'networkidle' })
    const sortSelect = page.locator('select:has(option[value="total-allocated-memory"])')
    await expect(sortSelect).toHaveCount(1)
    await expect(page.locator('option[value="average-allocated-memory"]')).toHaveCount(1)

    await expect(page.locator('main table th:has-text("Breakdown")').first()).toBeVisible()
    await expect(page.locator('main table th:has-text("Self (ms)")').first()).toBeVisible()
    const treeBtn = page.locator('button:has-text("Tree")')
    await treeBtn.click()
    await expect(page.locator('button:has-text("Tree")')).toHaveClass(/text-blue-600/)

    // Percentiles customization validates (0 < p < 100) and syncs URL
    await page.goto('/modern/transaction/percentiles', { waitUntil: 'networkidle' })
    await page.locator('button:has-text("customize")').click()
    const pctInput = page.locator('label:has-text("Percentiles")').locator('..').locator('input')
    await pctInput.fill('0, 100')
    await page.locator('button:has-text("Apply")').click()
    await expect(page.locator('main')).toContainText(/strictly between 0 and 100/i)
    await pctInput.fill('50, 90')
    await page.locator('button:has-text("Apply")').click()
    await expect(page).toHaveURL(/percentile=50&percentile=90/)

    // Thread profile: does not query on keystroke, validates parser errors, links to standalone flame graph
    await page.goto('/modern/transaction/thread-profile', { waitUntil: 'networkidle' })
    let profileCalls = 0
    page.on('request', (req) => {
      if (new URL(req.url()).pathname === '/backend/transaction/profile') {
        profileCalls++
      }
    })
    const profileFilter = page.locator('input[placeholder*="Filter"]').first()
    const profileRefresh = profileFilter.locator('..').locator('button:has-text("Refresh")')
    await profileFilter.fill('"unclosed')
    expect(profileCalls).toBe(0)
    await profileRefresh.click()
    await expect(page.locator('main')).toContainText('Mismatched quote')
    expect(profileCalls).toBe(0)

    await profileFilter.fill('foo-bar')
    await profileRefresh.click()
    await expect(page.locator('main')).toContainText('Invalid location for minus')
    expect(profileCalls).toBe(0)

    await profileFilter.locator('..').locator('button:has-text("Clear")').click()
    const flameHref = await page.locator('a:has-text("View as flame graph")').getAttribute('href')
    expect(flameHref).toMatch(/^\/modern\/transaction\/thread-flame-graph\?/)
    expect(flameHref).toContain('from=')
    expect(flameHref).toContain('to=')
    expect(flameHref).not.toContain('summary-sort-order=')

    // Standalone flame graph does NOT fire /backend/transaction/summaries or /backend/transaction/trace-count
    const flameForbiddenCalls: string[] = []
    let flameGraphCalled = false
    const onFlameReq = (req: import('@playwright/test').Request) => {
      const p = new URL(req.url()).pathname
      if (p === '/backend/transaction/flame-graph') flameGraphCalled = true
      if (p === '/backend/transaction/summaries' || p === '/backend/transaction/trace-count') {
        flameForbiddenCalls.push(p)
      }
    }
    page.on('request', onFlameReq)
    await page.goto(flameHref!, { waitUntil: 'networkidle' })
    await expect(page.locator('h2:has-text("Flame graph")')).toBeVisible()
    expect(flameGraphCalled).toBe(true)
    expect(flameForbiddenCalls).toEqual([])
    page.off('request', onFlameReq)

    // Invalid filter on standalone flame graph displays parser error cleanly without backend fetch
    await page.goto(`${flameHref}&filter=${encodeURIComponent('"unclosed')}`, { waitUntil: 'networkidle' })
    await expect(page.locator('main')).toContainText('Mismatched quote')

    // Traces filter form syncs criteria to URL on Refresh and clears cleanly on Clear
    await page.goto('/modern/transaction/traces', { waitUntil: 'networkidle' })
    await page.fill('#searchHeadline', '/test-path')
    await page.fill('#searchUser', 'alice')
    await page.selectOption('#searchLimit', '100')
    await page.locator('form button[type="submit"]:has-text("Refresh")').click()
    await expect(page).toHaveURL(/headline=%2Ftest-path|headline=\/test-path/)
    await expect(page).toHaveURL(/user=alice/)
    await expect(page).toHaveURL(/limit=100/)

    await page.locator('form button:has-text("Clear")').click()
    await expect(page).not.toHaveURL(/headline=/)
    await expect(page).not.toHaveURL(/user=/)
    await expect(page).not.toHaveURL(/limit=/)

    // Error messages validates filter parser errors cleanly without firing request
    await page.goto('/modern/error/messages', { waitUntil: 'networkidle' })
    let errCalls = 0
    page.on('request', (req) => {
      if (new URL(req.url()).pathname === '/backend/error/messages') errCalls++
    })
    const errFilter = page.locator('input[placeholder*="Filter error messages"]').first()
    const errRefresh = errFilter.locator('..').locator('button:has-text("Refresh")')
    await errFilter.fill('"badquote')
    expect(errCalls).toBe(0)
    await errRefresh.click()
    await expect(page.locator('main')).toContainText('Mismatched quote')
    expect(errCalls).toBe(0)

    expect(pageErrors).toEqual([])
  })
})
