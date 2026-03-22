import { test, expect } from '@playwright/test'

const EXPECTED_BACKEND_ROUTES: Array<{ route: string; expectedBackendPath: string }> = [
  { route: '/modern/transaction/average', expectedBackendPath: '/backend/transaction/average' },
  { route: '/modern/transaction/percentiles', expectedBackendPath: '/backend/transaction/percentiles' },
  { route: '/modern/transaction/throughput', expectedBackendPath: '/backend/transaction/throughput' },
  { route: '/modern/transaction/traces', expectedBackendPath: '/backend/transaction/points' },
  { route: '/modern/transaction/queries', expectedBackendPath: '/backend/transaction/queries' },
  { route: '/modern/transaction/service-calls', expectedBackendPath: '/backend/transaction/service-calls' },
  { route: '/modern/transaction/thread-profile', expectedBackendPath: '/backend/transaction/profile' },
  { route: '/modern/transaction/thread-flame-graph', expectedBackendPath: '/backend/transaction/flame-graph' },
  { route: '/modern/error/messages', expectedBackendPath: '/backend/error/messages' },
  { route: '/modern/error/traces', expectedBackendPath: '/backend/error/points' },
  { route: '/modern/jvm/gauges', expectedBackendPath: '/backend/jvm/gauges' },
  { route: '/modern/jvm/thread-dump', expectedBackendPath: '/backend/jvm/thread-dump' },
  { route: '/modern/jvm/jstack', expectedBackendPath: '/backend/jvm/jstack' },
  { route: '/modern/jvm/heap-dump', expectedBackendPath: '/backend/jvm/heap-dump-default-dir' },
  { route: '/modern/jvm/mbean-tree', expectedBackendPath: '/backend/jvm/mbean-tree' },
  { route: '/modern/jvm/system-properties', expectedBackendPath: '/backend/jvm/system-properties' },
  { route: '/modern/jvm/environment', expectedBackendPath: '/backend/jvm/environment' },
  { route: '/modern/jvm/capabilities', expectedBackendPath: '/backend/jvm/capabilities' },
  { route: '/modern/config/transaction', expectedBackendPath: '/backend/config/transaction' },
  { route: '/modern/config/gauge-list', expectedBackendPath: '/backend/config/gauges' },
  { route: '/modern/config/jvm', expectedBackendPath: '/backend/config/jvm' },
  { route: '/modern/config/alert-list', expectedBackendPath: '/backend/config/alerts' },
  { route: '/modern/config/ui-defaults', expectedBackendPath: '/backend/config/ui-defaults' },
  { route: '/modern/config/plugin-list', expectedBackendPath: '/backend/config/plugins' },
  { route: '/modern/config/instrumentation-list', expectedBackendPath: '/backend/config/instrumentation' },
  { route: '/modern/config/advanced', expectedBackendPath: '/backend/config/advanced' },
  { route: '/modern/config/json', expectedBackendPath: '/backend/config/json' },
  { route: '/modern/admin/general', expectedBackendPath: '/backend/admin/general' },
  { route: '/modern/admin/json', expectedBackendPath: '/backend/admin/json' },
]

test.describe('Phase A embedded UI parity', () => {
  test('default /modern/ route redirects and syncs transaction-type into URL', async ({ page }) => {
    const backendResponses: number[] = []
    page.on('response', (res) => {
      if (new URL(res.url()).pathname.startsWith('/backend/')) {
        backendResponses.push(res.status())
      }
    })

    await page.goto('/modern/', { waitUntil: 'networkidle' })
    await expect(page).toHaveURL(/\/modern\/transaction\/average/)
    await expect(page).toHaveURL(/transaction-type=/)
    expect(backendResponses.length).toBeGreaterThan(0)
    for (const status of backendResponses) {
      expect(status).toBeLessThan(400)
    }
  })

  test('embedded routes issue expected /backend/* calls and never stall on Loading...', async ({ page }) => {
    for (const { route, expectedBackendPath } of EXPECTED_BACKEND_ROUTES) {
      const seenPaths: string[] = []
      const failedStatuses: Array<{ url: string; status: number }> = []
      const pageErrors: string[] = []

      const onResponse = (res: import('@playwright/test').Response) => {
        const pathname = new URL(res.url()).pathname
        if (pathname.startsWith('/backend/')) {
          seenPaths.push(pathname)
          if (res.status() >= 400) {
            failedStatuses.push({ url: pathname, status: res.status() })
          }
        }
      }
      const onPageError = (err: Error) => pageErrors.push(err.message)

      page.on('response', onResponse)
      page.on('pageerror', onPageError)

      await page.goto(route, { waitUntil: 'networkidle' })
      await expect
        .poll(() => seenPaths.includes(expectedBackendPath), {
          message: `Expected ${route} to request ${expectedBackendPath}, saw: ${seenPaths.join(', ')}`,
          timeout: 10000,
        })
        .toBe(true)

      await expect(page.locator('main')).not.toHaveText(/^\s*Loading\.\.\.\s*$/)
      expect(failedStatuses, `Unexpected error responses on ${route}`).toEqual([])
      expect(pageErrors, `Uncaught page errors on ${route}`).toEqual([])

      page.off('response', onResponse)
      page.off('pageerror', onPageError)
    }
  })

  test('navbar links, embedded config path, query preservation, gears toggle, and mobile menu', async ({ page }) => {
    await page.goto('/modern/transaction/average?last=1800000', { waitUntil: 'networkidle' })

    // Classic UI link points to classic route with query string preserved
    const classicHref = await page.locator('nav a:has-text("Classic UI")').getAttribute('href')
    expect(classicHref).toMatch(/^\/transaction\/average\?/)
    expect(classicHref).toContain('last=1800000')

    // Classic UI's Try the new UI link sits in the navbar (not footer) and preserves current page & query params
    await page.goto('/transaction/average?agent-id=spring-rabbitmq&transaction-type=Background', {
      waitUntil: 'networkidle',
    })
    const tryNewUiLink = page.locator('nav a:has-text("Try the new UI")')
    await expect(tryNewUiLink).toBeVisible()
    await expect(page.locator('#footer')).not.toContainText('Try the new UI')
    await expect(tryNewUiLink).toHaveAttribute(
      'href',
      'modern/transaction/average?agent-id=spring-rabbitmq&transaction-type=Background',
    )
    await tryNewUiLink.click()
    await expect(page).toHaveURL(
      /\/modern\/transaction\/average\?agent-id=spring-rabbitmq&transaction-type=Background$/,
    )
    await expect(page.locator('nav a:has-text("Classic UI")')).toHaveAttribute(
      'href',
      '/transaction/average?agent-id=spring-rabbitmq&transaction-type=Background',
    )

    await page.goto('/modern/transaction/average?last=1800000', { waitUntil: 'networkidle' })

    // Nav links preserve transaction-type and last
    const errorsHref = await page.locator('nav a:has-text("Errors")').getAttribute('href')
    expect(errorsHref).toContain('/modern/error/messages')
    expect(errorsHref).toContain('last=1800000')
    expect(errorsHref).toContain('transaction-type=')

    // Gears menu toggles on click and points Configuration to /modern/config/transaction in embedded mode
    await page.click('#gtGearsMenuToggle')
    const gearsMenu = page.locator('#gtGearsMenu')
    await expect(gearsMenu).toBeVisible()
    const configHref = await gearsMenu.locator('a:has-text("Configuration")').getAttribute('href')
    expect(configHref).toBe('/modern/config/transaction')

    // Mobile menu displays navigation and Configuration link
    await page.setViewportSize({ width: 375, height: 812 })
    await page.click('button[aria-label="Toggle navigation"]')
    await expect(page.locator('nav a:has-text("Configuration")')).toBeVisible()
    await expect(page.locator('nav a:has-text("Administration")')).toBeVisible()
  })

  test('TimeSeriesChart overlay handles double-click zoom and hover; TracePointChart keeps points above overlay', async ({ page }) => {
    await page.goto('/modern/transaction/average', { waitUntil: 'networkidle' })
    const overlay = page.locator('svg g.brush rect.overlay').first()
    await expect(overlay).toBeVisible()
    const box = await overlay.boundingBox()
    expect(box).not.toBeNull()
    if (box) {
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
      await page.mouse.dblclick(box.x + box.width / 2, box.y + box.height / 2)
      await expect(page).toHaveURL(/from=\d+&to=\d+/)
    }

    await page.goto('/modern/transaction/traces', { waitUntil: 'networkidle' })
    const traceSvg = page.locator('svg').filter({ has: page.locator('g.brush') }).first()
    await expect(traceSvg.locator('g.brush rect.overlay')).toBeVisible()
    const orderOk = await traceSvg.evaluate((svg) => {
      const brush = svg.querySelector('g.brush')
      const clipped = svg.querySelector('g[clip-path]')
      if (!brush || !clipped || !brush.parentElement) return false
      const children = Array.from(brush.parentElement.children)
      return children.indexOf(brush) < children.indexOf(clipped)
    })
    expect(orderOk).toBe(true)
  })

  test('config JSON, admin JSON, and login guard work in embedded mode', async ({ page }) => {
    await page.goto('/modern/config/json', { waitUntil: 'networkidle' })
    await expect(page.locator('textarea')).not.toHaveValue('')

    await page.goto('/modern/admin/json', { waitUntil: 'networkidle' })
    await expect(page.locator('textarea')).not.toHaveValue('')

    // Login is disabled in embedded sandbox without password, so /modern/login redirects away
    await page.goto('/modern/login', { waitUntil: 'networkidle' })
    await expect(page).not.toHaveURL(/\/modern\/login$/)
  })
})
