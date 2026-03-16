/*
 * API client for Glowroot backend.
 *
 * Replicates the layout version checking logic from the AngularJS HTTP interceptor
 * (app.js lines 34-145). On every backend response the Glowroot-Layout-Version header
 * is compared against the current layout version; if different the layout is refreshed.
 */

import { encodeObject } from './query-strings'

type LayoutRefreshFn = () => void
type LoginRedirectFn = (timedOut: boolean) => void

export type QueryParams = Record<string, string | string[] | number | undefined>

let layoutVersion: string | null = null
let agentRollupLayoutVersion: string | null = null
let onLayoutRefresh: LayoutRefreshFn | null = null
let onAgentRollupLayoutRefresh: LayoutRefreshFn | null = null
let onLoginRedirect: LoginRedirectFn | null = null

export function setLayoutVersion(version: string | null) {
  layoutVersion = version
}

export function setAgentRollupLayoutVersion(version: string | null) {
  agentRollupLayoutVersion = version
}

export function setOnLayoutRefresh(fn: LayoutRefreshFn) {
  onLayoutRefresh = fn
}

export function setOnAgentRollupLayoutRefresh(fn: LayoutRefreshFn) {
  onAgentRollupLayoutRefresh = fn
}

export function setOnLoginRedirect(fn: LoginRedirectFn) {
  onLoginRedirect = fn
}

export function getContextPath(): string {
  const raw =
    ((window as unknown as Record<string, unknown>).contextPath as string | undefined) || ''
  const trimmed = raw.replace(/\/+$/, '')
  return trimmed
}

export function toClassicUrl(modernPathname: string, search = ''): string {
  const stripped = modernPathname.replace(/^\/modern/, '') || '/'
  return getContextPath() + stripped + search
}

function buildUrl(path: string, params?: QueryParams): string {
  let url = getContextPath() + path
  if (params) {
    const filtered: Record<string, unknown> = {}
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined) {
        filtered[key] = value
      }
    }
    const qs = encodeObject(filtered)
    if (qs) {
      url += url.includes('?') ? '&' + qs.slice(1) : qs
    }
  }
  return url
}

export function generateEncryptionKeyMessage(context: string): string {
  let message =
    'cassandra.symmetricEncryptionKey must be configured in the glowroot-central.properties' +
    ` file before ${context}` +
    '\n\ncassandra.symmetricEncryptionKey can be any 32 character hex string'
  if (typeof window !== 'undefined' && window.crypto?.getRandomValues) {
    const array = new Uint32Array(4)
    window.crypto.getRandomValues(array)
    let symmetricEncryptionKey = ''
    for (let i = 0; i < array.length; i++) {
      symmetricEncryptionKey += ('0000000' + array[i].toString(16)).slice(-8)
    }
    message +=
      ", e.g. here's one generated right now in your browser that you could use: " +
      symmetricEncryptionKey
  }
  return message
}

function checkLayoutVersion(headers: Headers) {
  const newVersion = headers.get('Glowroot-Layout-Version')
  if (newVersion && layoutVersion && newVersion !== layoutVersion) {
    onLayoutRefresh?.()
  }
  const newAgentRollupVersion = headers.get('Glowroot-Agent-Rollup-Layout-Version')
  if (
    newAgentRollupVersion &&
    agentRollupLayoutVersion &&
    newAgentRollupVersion !== agentRollupLayoutVersion
  ) {
    onAgentRollupLayoutRefresh?.()
  }
}

async function performFetch(url: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init)
  } catch (e) {
    if (e instanceof TypeError) {
      await new Promise((resolve) => setTimeout(resolve, 500))
      throw new ApiError(0, 'Unable to connect to server')
    }
    throw e
  }
}

async function handleResponse(response: Response): Promise<Response> {
  checkLayoutVersion(response.headers)

  if (response.status === 401) {
    const data = await response.json().catch(() => ({}))
    onLoginRedirect?.(!!data.timedOut)
    // return a never-resolving promise (same pattern as the AngularJS interceptor)
    return new Promise(() => {})
  }

  return response
}

async function executeRequest(url: string, init: RequestInit): Promise<string> {
  const response = await handleResponse(await performFetch(url, init))
  if (!response.ok) {
    const errorData = await response.json().catch(() => null)
    throw new ApiError(response.status, errorData?.message, errorData?.stackTrace)
  }
  return response.text()
}

export async function apiGetText(
  path: string,
  params?: QueryParams
): Promise<string> {
  return executeRequest(buildUrl(path, params), { credentials: 'same-origin' })
}

export async function apiGet<T>(
  path: string,
  params?: QueryParams
): Promise<T> {
  const text = await apiGetText(path, params)
  if (!text) return undefined as unknown as T
  return JSON.parse(text)
}

export async function apiPostText(
  path: string,
  body?: unknown,
  params?: QueryParams
): Promise<string> {
  return executeRequest(buildUrl(path, params), {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
}

export async function apiPost<T>(
  path: string,
  body?: unknown,
  params?: QueryParams
): Promise<T> {
  const text = await apiPostText(path, body, params)
  if (!text) return undefined as unknown as T
  return JSON.parse(text)
}

export class ApiError extends Error {
  status: number
  stackTrace?: string

  constructor(status: number, message?: string, stackTrace?: string) {
    super(message || `HTTP ${status}`)
    this.status = status
    this.stackTrace = stackTrace
  }
}
