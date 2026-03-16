/*
 * TypeScript types matching the JSON produced by LayoutService.java
 */

export interface RollupConfig {
  intervalMillis: number
  viewThresholdMillis: number
}

export interface TransactionPermissions {
  overview: boolean
  traces: boolean
  queries: boolean
  serviceCalls: boolean
  threadProfile: boolean
}

export interface ErrorPermissions {
  overview: boolean
  traces: boolean
}

export interface JvmPermissions {
  gauges: boolean
  threadDump: boolean
  heapDump: boolean
  heapHistogram: boolean
  forceGC: boolean
  mbeanTree: boolean
  systemProperties: boolean
  environment: boolean
  capabilities: boolean
}

export interface EditConfigPermissions {
  general: boolean
  transaction: boolean
  gauges: boolean
  jvm: boolean
  syntheticMonitors: boolean
  alerts: boolean
  uiDefaults: boolean
  plugins: boolean
  instrumentation: boolean
  advanced: boolean
  all: boolean
}

export interface ConfigPermissions {
  view: boolean
  edit: EditConfigPermissions
}

export interface Permissions {
  transaction: TransactionPermissions
  error: ErrorPermissions
  jvm: JvmPermissions
  syntheticMonitor: boolean
  incident: boolean
  config: ConfigPermissions
}

export interface AgentRollupLayout {
  id: string
  topLevelId: string
  topLevelDisplay: string
  childDisplay: string
  lastDisplayPart: string
  glowrootVersion: string
  permissions: Permissions
  transactionTypes: string[]
  traceAttributeNames: Record<string, string[]>
  defaultTransactionType: string
  defaultPercentiles: number[]
  defaultGaugeNames: string[]
  version: string
}

export interface Layout {
  central: boolean
  offlineViewer: boolean
  glowrootVersion: string
  loginEnabled: boolean
  rollupConfigs: RollupConfig[]
  rollupExpirationMillis: number[]
  queryAndServiceCallRollupExpirationMillis: number[]
  profileRollupExpirationMillis: number[]
  gaugeCollectionIntervalMillis: number
  showNavbarTransaction: boolean
  showNavbarError: boolean
  showNavbarJvm: boolean
  showNavbarSyntheticMonitor: boolean
  showNavbarIncident: boolean
  showNavbarReport: boolean
  showNavbarConfig: boolean
  adminView: boolean
  adminEdit: boolean
  loggedIn: boolean
  ldap: boolean
  redirectToLogin: boolean
  defaultTimeZoneId: string
  timeZoneIds: string[]
  embeddedAgentRollup: AgentRollupLayout | null
  version: string
}
