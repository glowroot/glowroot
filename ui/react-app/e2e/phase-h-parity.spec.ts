import { test, expect } from '@playwright/test'

test.describe('Phase H - Offline Trace Export, Typography & Classic Visual Spirit', () => {
  test('H1 Offline trace export ZIP download and TraceModal flame graph filter link', async ({
    page,
    request,
  }) => {
    const now = Date.now()
    let capturedExportUrl = ''

    await page.route('**/backend/transaction/points*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          normalPoints: [[now - 1000, 120_000_000, '', 'trace-export-test']],
          errorPoints: [],
          partialPoints: [],
          expired: false,
          limitExceeded: false,
        }),
      })
    })
    await page.route('**/backend/trace/header*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          expired: false,
          headline: 'GET /export-check',
          transactionType: 'Web',
          transactionName: '/export-check',
          startTime: now - 1000,
          captureTime: now,
          durationNanos: 120_000_000,
          active: false,
          mainThreadRootTimer: {
            name: 'http request',
            totalNanos: 120_000_000,
            count: 1,
          },
          entryCount: 0,
          entryLimitExceeded: false,
          queryCount: 0,
          queryLimitExceeded: false,
          mainThreadProfileSampleCount: 10,
          auxThreadProfileSampleCount: 0,
          entriesExistence: 'no',
          queriesExistence: 'no',
          profileExistence: 'yes',
        }),
      })
    })
    await page.route('**/backend/trace/main-thread-profile*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          unfilteredSampleCount: 10,
          rootNodes: [
            {
              stackTraceElement: 'com.example.Service.process(Service.java:42)',
              sampleCount: 10,
              childNodes: [],
            },
          ],
        }),
      })
    })
    await page.route('**/export/trace*', async (route) => {
      capturedExportUrl = route.request().url()
      await route.fulfill({
        status: 200,
        headers: {
          'content-type': 'application/zip',
          'content-disposition': 'attachment; filename="trace-export-test.zip"',
        },
        body: Buffer.from('PK\x05\x06' + '\x00'.repeat(18), 'binary'),
      })
    })

    await page.goto('/modern/transaction/traces?modal-trace-id=trace-export-test', {
      waitUntil: 'networkidle',
    })

    const modal = page.getByRole('dialog')
    await expect(modal).toBeVisible()

    // Open Profile section, filter tree, and check View flame graph link preserves filter
    await modal.getByRole('button', { name: /^Profile \(10\)/ }).click()
    await modal.getByPlaceholder('Filter the profile tree').fill('com.example.Service')
    const flameLink = modal.getByRole('link', { name: 'View flame graph' })
    const flameHref = await flameLink.getAttribute('href')
    expect(flameHref).toContain('/modern/transaction/trace-thread-flame-graph?')
    expect(flameHref).toContain('trace-id=trace-export-test')
    expect(flameHref).toContain('filter=com.example.Service')

    // Click Download trace button and verify /export/trace URL
    await Promise.all([
      page.waitForEvent('download'),
      modal.locator('button.download-trace').click(),
    ])
    await expect.poll(() => capturedExportUrl).toContain('/export/trace?trace-id=trace-export-test')

    // Verify live TraceExportHttpService endpoint directly
    const layoutResp = await request.get('/backend/layout')
    expect(layoutResp.status()).toBe(200)
    const layoutJson = await layoutResp.json()
    const defaultTxnType =
      layoutJson?.embeddedAgentRollup?.defaultTransactionType || 'Sandbox'
    const pointsResp = await request.get(
      `/backend/transaction/points?agent-rollup-id=&transaction-type=${encodeURIComponent(defaultTxnType)}&from=0&to=${Date.now()}&duration-millis-low=0&headline-comparator=begins&headline=&error-message-comparator=begins&error-message=&user-comparator=begins&user=&attribute-name=&attribute-value-comparator=begins&attribute-value=&limit=10`
    )
    expect(pointsResp.status()).toBe(200)
    const pointsJson = await pointsResp.json()
    const livePoint =
      pointsJson.normalPoints?.[0] ??
      pointsJson.errorPoints?.[0] ??
      pointsJson.partialPoints?.[0]
    if (livePoint && typeof livePoint[3] === 'string') {
      const liveTraceId = livePoint[3]
      const exportResp = await request.get(
        `/export/trace?trace-id=${encodeURIComponent(liveTraceId)}`
      )
      expect(exportResp.status()).toBe(200)
      expect(exportResp.headers()['content-type'] ?? '').toContain('application/zip')
    } else {
      const missingResp = await request.get('/export/trace?trace-id=nonexistent-trace-id')
      expect(missingResp.status()).toBe(404)
    }
  })

  test('H2 Classic Glowroot typography (Open Sans & Cousine) and bundled WOFF2 font assets', async ({
    page,
  }) => {
    await page.goto('/modern/transaction/average', { waitUntil: 'networkidle' })

    const fontCheck = await page.evaluate(() => {
      const bodyFont = getComputedStyle(document.body).fontFamily
      const families: string[] = []
      const woff2Urls: string[] = []

      for (const sheet of Array.from(document.styleSheets)) {
        const base = sheet.href || document.baseURI
        let rules: CSSRuleList
        try {
          rules = sheet.cssRules
        } catch {
          continue
        }
        for (const rule of Array.from(rules)) {
          if (rule instanceof CSSFontFaceRule) {
            const fam = rule.style.getPropertyValue('font-family').replace(/['"]/g, '').trim()
            if (fam) families.push(fam)
            const src = rule.style.getPropertyValue('src')
            const regex = /url\(["']?([^"')]+\.woff2)["']?\)/g
            let m: RegExpExecArray | null
            while ((m = regex.exec(src)) !== null) {
              woff2Urls.push(new URL(m[1], base).toString())
            }
          }
        }
      }

      return { bodyFont, families, woff2Urls }
    })

    expect(fontCheck.bodyFont).toContain('Open Sans')
    expect(fontCheck.families).toContain('Open Sans')
    expect(fontCheck.families).toContain('Cousine')
    expect(fontCheck.woff2Urls.length).toBeGreaterThan(0)

    const fontAssetResp = await page.request.get(fontCheck.woff2Urls[0])
    expect(fontAssetResp.status()).toBe(200)
  })

  test('H3 Classic Glowroot visual color palette across Navbar, Page Header, Sidebar, Buttons, Footer, and Incidents', async ({
    page,
  }) => {
    await page.goto('/modern/transaction/average', { waitUntil: 'networkidle' })

    await expect(page.locator('nav.gt-navbar')).toHaveCSS(
      'background-color',
      'rgb(142, 108, 21)'
    )
    await expect(page.locator('nav.gt-navbar a:has-text("Transactions")')).toHaveCSS(
      'background-color',
      'rgb(120, 91, 18)'
    )
    await expect(page.locator('h1.gt-header-text')).toHaveCSS('color', 'rgb(142, 108, 21)')
    await expect(page.locator('footer')).toHaveCSS('background-color', 'rgb(237, 234, 228)')

    await page.goto('/modern/config/transaction', { waitUntil: 'networkidle' })
    await expect(page.locator('.gt-sidebar').first()).toHaveCSS(
      'border-top-color',
      'rgb(210, 186, 127)'
    )
    await expect(page.locator('.gt-sidebar a', { hasText: 'Transactions' })).toHaveCSS(
      'background-color',
      'rgb(237, 234, 228)'
    )
    await expect(page.getByRole('button', { name: 'Save changes' })).toHaveCSS(
      'background-color',
      'rgb(65, 121, 152)'
    )

    await page.goto('/modern/incidents', { waitUntil: 'networkidle' })
    await expect(page.locator('.gt-incident-active')).toHaveCSS(
      'border-top-color',
      'rgb(191, 56, 11)'
    )
  })
})
