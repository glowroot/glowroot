import { test, expect } from '@playwright/test'

test.describe('Phase C UI & API parity', () => {
  test('Group 1 (TRC Modal): trace point click opens modal, loads header/sections, supports download link and close', async ({ page }) => {
    const pageErrors: string[] = []
    const failedRequests: Array<{ url: string; status: number }> = []
    page.on('pageerror', (err) => pageErrors.push(err.message))
    page.on('response', (res) => {
      const pathname = new URL(res.url()).pathname
      if (pathname.startsWith('/backend/') && res.status() >= 400) {
        failedRequests.push({ url: pathname, status: res.status() })
      }
    })

    const pointsPromise = page.waitForResponse(
      (res) => new URL(res.url()).pathname === '/backend/transaction/points' && res.status() === 200
    )
    await page.goto('/modern/transaction/traces?transaction-type=Sandbox', { waitUntil: 'networkidle' })
    const pointsRes = await pointsPromise
    const pointsJson = (await pointsRes.json()) as {
      normalPoints?: Array<[number, number, string, string, boolean]>
      errorPoints?: Array<[number, number, string, string, boolean]>
      partialPoints?: Array<[number, number, string, string, boolean]>
    }

    const totalPoints =
      (pointsJson.normalPoints?.length ?? 0) +
      (pointsJson.errorPoints?.length ?? 0) +
      (pointsJson.partialPoints?.length ?? 0)
    expect(totalPoints).toBeGreaterThan(0)

    const firstPoint = page.locator('.trace-point-circle, .trace-point-triangle, .trace-point-diamond').first()
    await expect(firstPoint).toBeVisible()

      const headerPromise = page.waitForResponse(
        (res) => new URL(res.url()).pathname === '/backend/trace/header' && res.status() === 200
      )
      await firstPoint.click({ force: true })
      await headerPromise

    const modal = page.locator('#traceModal')
    await expect(modal).toBeVisible()
    await expect(page).toHaveURL(/modal-trace-id=/)

    const downloadBtn = modal.locator('button[aria-label="Download trace"], a[aria-label="Download trace"], button:has-text("Download trace"), a:has-text("Download trace")')
    await expect(downloadBtn.first()).toBeVisible()
    await expect(modal.locator('.gt-trace-parent')).toContainText(/Duration:/)

    const entriesBtn = modal.locator('button:has-text("Trace entries")')
    if ((await entriesBtn.count()) > 0) {
      const entriesResPromise = page.waitForResponse(
        (res) => new URL(res.url()).pathname === '/backend/trace/entries' && res.status() === 200
      )
      await entriesBtn.first().click()
      await entriesResPromise
    }

    await page.keyboard.press('Escape')
    await expect(modal).toHaveCount(0)
    await expect(page).not.toHaveURL(/modal-trace-id=/)

    expect(pageErrors, 'Uncaught page errors on TRC Modal').toEqual([])
    expect(failedRequests, 'HTTP >=400 on TRC Modal').toEqual([])
  })

  test('Group 2 (TRC Flame Graph & Thread Dump): flame-graph validates filter, renders real trace flame graph, and thread dump loads', async ({ page, request }) => {
    const pageErrors: string[] = []
    page.on('pageerror', (err) => pageErrors.push(err.message))

    // Invalid filter must not call backend flame-graph endpoint
    let flameGraphCalled = false
    const trackFlame = (req: import('@playwright/test').Request) => {
      if (new URL(req.url()).pathname === '/backend/transaction/traces/flame-graph') {
        flameGraphCalled = true
      }
    }
    page.on('request', trackFlame)
    await page.goto('/modern/transaction/trace-thread-flame-graph?trace-id=abc&filter=%22', {
      waitUntil: 'networkidle',
    })
    await expect(page.locator('main')).toContainText(/Mismatched quote/i)
    expect(flameGraphCalled).toBe(false)
    page.off('request', trackFlame)

    // Pick a real trace ID from sandbox so flame graph renders actual frames if profiled
    const now = Date.now()
    const ptsResp = await request.get(
      `/backend/transaction/points?transaction-type=Sandbox&from=${now - 14400000}&to=${now}&duration-millis-low=0&headline-comparator=begins&headline=&error-message-comparator=begins&error-message=&user-comparator=begins&user=&attribute-name=&attribute-value-comparator=begins&attribute-value=&limit=50&agent-rollup-id=`
    )
    expect(ptsResp.status()).toBe(200)
    const pts = await ptsResp.json()
    const realTraceId: string =
      pts.normalPoints?.[0]?.[3] || pts.errorPoints?.[0]?.[3] || pts.partialPoints?.[0]?.[3] || 'nonexistent-trace'

    const flameResPromise = page.waitForResponse(
      (res) => new URL(res.url()).pathname === '/backend/transaction/traces/flame-graph'
    )
    await page.goto(
      `/modern/transaction/trace-thread-flame-graph?trace-id=${encodeURIComponent(realTraceId)}&truncate-branch-percentage=1`,
      { waitUntil: 'networkidle' }
    )
    const flameRes = await flameResPromise
    expect(flameRes.status()).toBe(200)
    const flameJson = await flameRes.json()
    if (flameJson.rootNodes?.length > 0) {
      await expect(page.locator('main svg')).toBeVisible()
    } else {
      await expect(page.locator('main')).toContainText(/No data for this time period/i)
    }

    // JVM Thread Dump page loads cleanly and any view trace link opens modal
    const dumpResPromise = page.waitForResponse(
      (res) => new URL(res.url()).pathname === '/backend/jvm/thread-dump'
    )
    await page.goto('/modern/jvm/thread-dump', { waitUntil: 'networkidle' })
    const dumpRes = await dumpResPromise
    expect(dumpRes.status()).toBe(200)
    await expect(page.locator('main pre')).toBeVisible()

    const viewTraceBtn = page.locator('button:has-text("view trace")').first()
    if ((await viewTraceBtn.count()) > 0) {
      await viewTraceBtn.click()
      await expect(page.locator('#traceModal')).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(page.locator('#traceModal')).toHaveCount(0)
    }

    expect(pageErrors).toEqual([])
  })

  test('Group 3 (OTH Synthetic Monitors & Incidents): routes load cleanly and handle central synthetic monitor interactions', async ({ page, request }) => {
    const pageErrors: string[] = []
    const failedRequests: Array<{ url: string; status: number }> = []
    page.on('pageerror', (err) => pageErrors.push(err.message))
    page.on('response', (res) => {
      const pathname = new URL(res.url()).pathname
      if (pathname.startsWith('/backend/') && res.status() >= 400) {
        failedRequests.push({ url: pathname, status: res.status() })
      }
    })

    const layoutRes = await request.get('/backend/layout')
    const layout = await layoutRes.json()

    if (!layout.central) {
      // Synthetic monitors backend service is only mounted in Central mode; mock realistic central payload
      const now = Date.now()
      const t1 = now - 60 * 60 * 1000
      const t2 = now - 30 * 60 * 1000
      await page.route('/backend/synthetic-monitor/results*', async (route) => {
        const reqUrl = new URL(route.request().url())
        const isAll = reqUrl.searchParams.get('all') === 'true'
        const selected = reqUrl.searchParams.getAll('synthetic-monitor-id')
        const activeIds = isAll ? ['mon-1', 'mon-2'] : selected
        const seriesMap: Record<string, { name: string; data: Array<[number, number]> }> = {
          'mon-1': { name: 'mon-1', data: [[t1, 120.5], [t2, 145.2]] },
          'mon-2': { name: 'mon-2', data: [[t1, 80.1], [t2, 92.4]] },
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            dataSeries: activeIds.map((id) => seriesMap[id]).filter(Boolean),
            dataPointIntervalMillis: 1800000,
            executionCounts: { 0: { [t1]: 5, [t2]: 6 }, 1: { [t1]: 5, [t2]: 6 } },
            markings: activeIds.length
              ? [{ from: t1, to: t2, intervals: { 'mon-1': [{ from: t1, to: t2, count: 2, message: 'Timeout' }] } }]
              : [],
            allSyntheticMonitors: [
              { id: 'mon-1', display: 'Homepage PING' },
              { id: 'mon-2', display: 'Login Flow' },
            ],
            displayNoSyntheticMonitorsConfigured: false,
          }),
        })
      })
    }

    const synthResPromise = page.waitForResponse(
      (res) => new URL(res.url()).pathname === '/backend/synthetic-monitor/results' && res.status() === 200
    )
    await page.goto('/modern/synthetic-monitors', { waitUntil: 'networkidle' })
    await synthResPromise
    await expect(page.locator('h1')).toContainText('Synthetic monitors')

    if (!layout.central) {
      await expect(page.locator('main')).toContainText('Homepage PING')
      await expect(page.locator('main')).toContainText('Login Flow')
      // Deselecting all monitors updates URL to ?synthetic-monitor-id= and shows selection prompt
      const checkboxes = page.locator('main input[type="checkbox"]')
      await checkboxes.nth(0).click()
      await checkboxes.nth(1).click()
      await expect(page).toHaveURL(/synthetic-monitor-id=$/)
      await expect(page.locator('main')).toContainText('Select one or more synthetic monitors below')
      await page.unroute('/backend/synthetic-monitor/results*')
    }

    // Incidents
    const incResPromise = page.waitForResponse(
      (res) => new URL(res.url()).pathname === '/backend/incidents'
    )
    await page.goto('/modern/incidents', { waitUntil: 'networkidle' })
    const incRes = await incResPromise
    expect(incRes.status()).toBe(200)
    await expect(page.locator('h1')).toContainText('Incidents')
    await expect(page.locator('.gt-incident-active')).toContainText('Open')
    await expect(page.locator('main')).toContainText('Resolved')
    await expect(page.locator('main')).toContainText('(last 30 days)')

    expect(pageErrors).toEqual([])
    expect(failedRequests).toEqual([])
  })

  test('Group 4 (OTH Ad-Hoc Report): hydrates without auto-run, validates, executes report, and supports CSV export', async ({ page }) => {
    const pageErrors: string[] = []
    const failedRequests: Array<{ url: string; status: number }> = []
    page.on('pageerror', (err) => pageErrors.push(err.message))
    page.on('response', (res) => {
      const pathname = new URL(res.url()).pathname
      if (pathname.startsWith('/backend/') && res.status() >= 400) {
        failedRequests.push({ url: pathname, status: res.status() })
      }
    })

    let reportRequestCount = 0
    page.on('request', (req) => {
      if (new URL(req.url()).pathname === '/backend/report') {
        reportRequestCount++
      }
    })

    const ttPromise = page.waitForResponse(
      (res) => new URL(res.url()).pathname === '/backend/report/transaction-types-and-gauges' && res.status() === 200
    )
    await page.goto('/modern/report/ad-hoc', { waitUntil: 'networkidle' })
    await ttPromise

    expect(reportRequestCount, 'Should not auto-run /backend/report on initial load').toBe(0)

    // Validation error when submitting without selecting metric
    await page.locator('button:has-text("Run report")').click()
    await expect(page.locator('form')).toContainText(/Metric is required|Select one or more agents/i)
    expect(reportRequestCount).toBe(0)

    // Select average response time + Sandbox transaction type and run report
    await page.locator('#reportMetric').selectOption('transaction:average')
    await expect(page.locator('#reportTransactionType option[value="Sandbox"]')).toHaveCount(1)
    await page.locator('#reportTransactionType').selectOption('Sandbox')

    const reportResPromise = page.waitForResponse(
      (res) => new URL(res.url()).pathname === '/backend/report'
    )
    await page.locator('button:has-text("Run report")').click()
    const reportRes = await reportResPromise
    expect(reportRes.status()).toBe(200)

    const csvButton = page.locator('button:has-text("Export as CSV")')
    if ((await csvButton.count()) > 0) {
      await expect(page.locator('main table')).toContainText('Overall')
      const popupPromise = page.waitForEvent('popup')
      await csvButton.click()
      const popup = await popupPromise
      await popup.waitForLoadState('domcontentloaded')
      await expect(popup.locator('pre')).toContainText('Overall')
      await popup.close()
    } else {
      await expect(page.locator('main')).toContainText('No data for this time period')
    }

    expect(pageErrors).toEqual([])
    expect(failedRequests).toEqual([])
  })

  test('Group 5 (Navbar Reporting link): Reporting link carries only from-date and to-date', async ({ page }) => {
    await page.goto(
      '/modern/transaction/average?transaction-type=Sandbox&transaction-name=%2Fhome&from=1700000000000&to=1700086400000',
      { waitUntil: 'networkidle' }
    )
    const reportingLink = page.locator('nav a[href*="/modern/report/ad-hoc"]').first()
    await expect(reportingLink).toBeVisible()
    const href = await reportingLink.getAttribute('href')
    expect(href).toBeTruthy()

    const url = new URL(href!, 'http://localhost:4000')
    expect(url.searchParams.has('agent-id')).toBe(false)
    expect(url.searchParams.has('agent-rollup-id')).toBe(false)
    expect(url.searchParams.has('transaction-type')).toBe(false)
    expect(url.searchParams.has('transaction-name')).toBe(false)
    expect(url.searchParams.has('from-date')).toBe(true)
    expect(url.searchParams.has('to-date')).toBe(true)
  })
})
