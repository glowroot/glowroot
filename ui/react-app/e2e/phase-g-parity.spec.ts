import { test, expect } from '@playwright/test'

test.describe('Phase G - Transactions, Errors, Trace Detail, JVM & Reporting Parity', () => {
  test('G1 Transaction layout shows sidebar and main headers, active tab/sub-nav refresh, keyboard navigation, and response time polish', async ({
    page,
  }) => {
    let avgCalls = 0
    await page.route('**/backend/transaction/average*', async (route) => {
      avgCalls++
      await route.continue()
    })

    await page.goto('/modern/transaction/average', { waitUntil: 'networkidle' })

    // Sidebar overall row and main card h2 active scope header
    await expect(page.locator('.w-52').getByText('All Sandbox Transactions')).toBeVisible()
    await expect(page.locator('h2', { hasText: 'All Sandbox Transactions' })).toBeVisible()

    // Active tab click refreshes without navigating away
    const beforeTabRefresh = avgCalls
    await page.locator('#tab_time').click()
    await expect(page).toHaveURL(/\/modern\/transaction\/average/)
    await expect.poll(() => avgCalls).toBeGreaterThan(beforeTabRefresh)

    // Active Average sub-nav link refreshes
    const beforeSubNavRefresh = avgCalls
    await page.getByRole('link', { name: 'Average', exact: true }).click()
    await expect.poll(() => avgCalls).toBeGreaterThan(beforeSubNavRefresh)

    // Chart navigation help popover
    const chartHelpBtn = page.getByRole('button', { name: 'Chart navigation' })
    await expect(chartHelpBtn).toBeVisible()
    await chartHelpBtn.click()
    await expect(page.getByText('Chart navigation tips')).toBeVisible()
    await expect(page.getByText('Drag or double click to zoom in')).toBeVisible()

    // Self-time vs inclusive breakdown note and Allocated memory help popover (if data rendered)
    const note = page.getByText(/The chart shows stacked self time \(exclusive\)/)
    if (await note.count()) {
      await expect(note).toBeVisible()
      const allocHelp = page.getByRole('button', { name: 'Allocated memory' })
      if (await allocHelp.count()) {
        await expect(allocHelp.first()).toBeVisible()
      }
    }

    // Left/Right arrow keyboard navigation between tabs
    await page.locator('#tab_time').focus()
    await page.keyboard.press('ArrowRight')
    await expect(page).toHaveURL(/\/modern\/transaction\/traces/)
    await page.locator('#tab_traces').focus()
    await page.keyboard.press('ArrowLeft')
    await expect(page).toHaveURL(/\/modern\/transaction\/average/)

    // Percentiles HdrHistogram link & chart help popover
    await page.goto('/modern/transaction/percentiles', { waitUntil: 'networkidle' })
    await expect(page.getByRole('button', { name: 'Chart navigation' })).toBeVisible()
    await page.getByRole('button', { name: 'customize' }).click()
    const hdrLink = page.getByRole('link', {
      name: 'Custom percentiles are calculated using HdrHistogram',
    })
    await expect(hdrLink).toHaveAttribute('href', 'http://hdrhistogram.github.io/HdrHistogram/')

    // Throughput summary wording & chart help popover
    await page.route('**/backend/transaction/throughput*', async (route) => {
      const now = Date.now()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          dataSeries: [
            {
              name: 'throughput',
              data: [
                [now - 60000, 12.5],
                [now, 15.0],
              ],
            },
          ],
          dataPointIntervalMillis: 60000,
          markings: [],
          transactionCount: 27,
          transactionsPerMin: 13.75,
        }),
      })
    })
    await page.goto('/modern/transaction/throughput', { waitUntil: 'networkidle' })
    await expect(page.getByRole('button', { name: 'Chart navigation' })).toBeVisible()
    await expect(page.getByText(/13\.8 transactions per minute/)).toBeVisible()
    await expect(page.getByText(/27 transactions in this period/)).toBeVisible()
  })

  test('G1 Transaction traces validation, displayed trace count, expired notice, thread profile chain & highlighting, and error tabs', async ({
    page,
  }) => {
    const now = Date.now()
    await page.route('**/backend/transaction/points*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          normalPoints: [
            [now - 30000, 120, '', 'trace-1'],
            [now - 10000, 240, '', 'trace-2'],
          ],
          errorPoints: [],
          partialPoints: [],
          expired: true,
          limitExceeded: false,
        }),
      })
    })

    await page.goto('/modern/transaction/traces', { waitUntil: 'networkidle' })

    // Badge count sync & expired notice & Clear criteria button
    await expect(page.locator('#tab_traces')).toContainText('Slow traces (2)')
    await expect(page.getByText(/Data has expired/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Clear criteria' })).toBeVisible()

    // Invalid duration filter criteria shows validation message
    await page.locator('#filterDurationMillisLow').fill('abc')
    await page.locator('form button[type="submit"]').click()
    await expect(page.getByText('Fix the invalid filter criteria')).toBeVisible()
    await page.getByRole('button', { name: 'Clear criteria' }).click()
    await expect(page.getByText('Fix the invalid filter criteria')).not.toBeVisible()

    // Thread profile chain collapsing, match highlighting, leafThreadState, truncated branches, and disabled Main thread
    await page.route('**/backend/transaction/profile*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          hasUnfilteredMainThreadProfile: false,
          hasUnfilteredAuxThreadProfile: true,
          profile: {
            unfilteredSampleCount: 100,
            rootNodes: [
              {
                stackTraceElement: 'com.example.RootEntry.run(RootEntry.java:10)',
                sampleCount: 100,
                childNodes: [
                  {
                    stackTraceElement: 'com.example.MiddleOne.step(MiddleOne.java:20)',
                    sampleCount: 100,
                    childNodes: [
                      {
                        stackTraceElement: 'com.example.LeafWorker.work(LeafWorker.java:30)',
                        sampleCount: 100,
                        ellipsedSampleCount: 12,
                        leafThreadState: 'RUNNABLE',
                        childNodes: [],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        }),
      })
    })

    await page.goto('/modern/transaction/thread-profile', { waitUntil: 'networkidle' })

    // Main thread button is disabled when only aux profile exists
    await expect(page.getByRole('button', { name: 'Main thread' })).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Auxiliary threads' })).toBeVisible()

    // Single-child chain renders '...' expand button, leafThreadState, and truncated branches footer
    const expandChainBtn = page.getByRole('button', { name: '...' })
    await expect(expandChainBtn).toBeVisible()
    await expect(page.getByText('RUNNABLE')).toBeVisible()
    await expect(page.getByText('12 samples in truncated branches')).toBeVisible()

    await expandChainBtn.click()
    await expect(page.getByText('com.example.MiddleOne.step(MiddleOne.java:20)')).toBeVisible()

    // Filter match highlighting wraps matched substring in <strong>
    const profileFilterInput = page.getByPlaceholder('Filter (e.g., com.example -sun.*)')
    await profileFilterInput.fill('LeafWorker')
    await profileFilterInput.locator('..').getByRole('button', { name: 'Refresh', exact: true }).click()
    await expect(page.locator('strong', { hasText: 'LeafWorker' }).first()).toBeVisible()

    // Error messages & traces tab labels
    await page.goto('/modern/error/messages', { waitUntil: 'networkidle' })
    await expect(page.locator('#tab_messages')).toHaveText('Error messages')
    await expect(page.locator('#tab_traces')).toContainText(/^Error traces \(/)
  })

  test('G2 TraceModal highlights profile filter matches and batches queries; JVM Gauges and Heap Histogram expose controls and text export', async ({
    page,
  }) => {
    // Open TraceModal directly via URL params with mocked header/entries/queries/profile
    await page.route('**/backend/trace/header*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          headline: 'GET /phase-g-trace',
          transactionType: 'Web',
          transactionName: '/phase-g-trace',
          startTime: Date.now() - 1000,
          captureTime: Date.now(),
          durationNanos: 120_000_000,
          active: false,
          mainThreadRootTimer: {
            name: 'http request',
            totalNanos: 120_000_000,
            count: 1,
          },
          entryCount: 0,
          entryLimitExceeded: false,
          queryCount: 5,
          queryLimitExceeded: false,
          mainThreadProfileSampleCount: 10,
          auxThreadProfileSampleCount: 0,
          entriesExistence: 'no',
          queriesExistence: 'yes',
          profileExistence: 'yes',
        }),
      })
    })
    await page.route('**/backend/trace/queries*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          overwritten: false,
          queries: [
            {
              type: 'SQL',
              sharedQueryTextIndex: 0,
              totalDurationNanos: 5_000_000,
              executionCount: 2,
              totalRows: 4,
            },
          ],
          sharedQueryTexts: [{ fullText: 'select * from phase_g_items' }],
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
              stackTraceElement: 'com.example.TraceTargetService.execute(TraceTargetService.java:42)',
              sampleCount: 10,
              childNodes: [],
            },
          ],
        }),
      })
    })

    await page.goto(
      '/modern/transaction/traces?modal-agent-id=&modal-trace-id=trace-phase-g',
      { waitUntil: 'networkidle' }
    )
    const modal = page.getByRole('dialog')
    await expect(modal).toBeVisible()
    await modal.getByRole('button', { name: /^Query stats/ }).click()
    await expect(modal.getByText('select * from phase_g_items')).toBeVisible()

    await modal.getByRole('button', { name: /^Profile \(10\)/ }).click()
    await modal.getByPlaceholder(/Filter/).fill('TraceTarget')
    await expect(modal.locator('strong', { hasText: 'TraceTarget' }).first()).toBeVisible()
    await page.keyboard.press('Escape')

    // JVM Gauges controls
    await page.goto('/modern/jvm/gauges', { waitUntil: 'networkidle' })
    await expect(page.locator('button[title="Zoom out"]')).toBeVisible()
    await expect(page.locator('button[title="Refresh"]')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Chart navigation' })).toBeVisible()
    await page.getByRole('button', { name: 'Chart navigation' }).click()
    await expect(page.getByText('Chart navigation tips')).toBeVisible()

    // JVM Heap Histogram Export as text button alongside Export as CSV
    await page.goto('/modern/jvm/heap-histogram', { waitUntil: 'networkidle' })
    await expect(page.getByRole('button', { name: 'Export as CSV' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Export as text' })).toBeVisible()
  })

  test('G3 Synthetic monitors help & Select/Deselect all and Ad-hoc reporting Central GtSelectpicker', async ({
    page,
    browser,
  }) => {
    await page.route('**/backend/synthetic-monitor/results*', async (route) => {
      const now = Date.now()
      const reqUrl = new URL(route.request().url())
      const isAll = reqUrl.searchParams.get('all') === 'true'
      const selected = reqUrl.searchParams.getAll('synthetic-monitor-id').filter(Boolean)
      const activeIds = isAll ? ['mon-1', 'mon-2'] : selected
      const seriesMap: Record<string, { name: string; data: Array<[number, number]> }> = {
        'mon-1': { name: 'mon-1', data: [[now - 60000, 15]] },
        'mon-2': { name: 'mon-2', data: [[now - 60000, 22]] },
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          dataSeries: activeIds.map((id) => seriesMap[id]).filter(Boolean),
          dataPointIntervalMillis: 60000,
          markings: [],
          executionCounts: {},
          allSyntheticMonitors: [
            { id: 'mon-1', display: 'Home Page' },
            { id: 'mon-2', display: 'Health Check' },
          ],
          displayNoSyntheticMonitorsConfigured: false,
        }),
      })
    })

    await page.goto('/modern/synthetic-monitors', { waitUntil: 'networkidle' })
    await expect(page.getByRole('button', { name: 'Chart navigation' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Select all', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Deselect all', exact: true })).toBeVisible()

    await page.getByRole('button', { name: 'Deselect all', exact: true }).click()
    await expect(page).toHaveURL(/synthetic-monitor-id=$/)
    await expect(page.getByText('Select one or more synthetic monitors below')).toBeVisible()

    await page.getByRole('button', { name: 'Select all', exact: true }).click()
    await expect(page).not.toHaveURL(/synthetic-monitor-id=/)

    // Ad-hoc reporting Central mode renders GtSelectpicker (#reportAgentRollupIdSelect)
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
      await centralPage.route('**/backend/report/agent-rollups*', async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            { id: 'agent-1', display: 'Agent One', lastDisplayPart: 'Agent One', depth: 0 },
            { id: 'grp::agent-2', display: 'Agent Two', lastDisplayPart: 'Agent Two', depth: 1 },
          ]),
        })
      })
      await centralPage.route('**/backend/report/transaction-types-and-gauges*', async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ transactionTypes: ['Web'], gauges: [] }),
        })
      })

      await centralPage.goto('/modern/report/ad-hoc', { waitUntil: 'networkidle' })
      const pickerContainer = centralPage.locator('#reportAgentRollupIdSelect')
      await expect(pickerContainer).toBeVisible()
      await pickerContainer.getByRole('button', { name: 'Agents' }).click()
      await expect(centralPage.getByRole('option', { name: 'Agent One' })).toBeVisible()
      await centralPage.getByRole('option', { name: 'Agent Two' }).click()
      await expect(pickerContainer).toContainText('Agent Two')
    } finally {
      await centralCtx.close()
    }
  })
})
