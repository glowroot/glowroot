import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { getContextPath } from '../lib/api'
import { LayoutProvider, useLayout } from '../contexts/LayoutContext'
import { useAgent } from '../contexts/AgentContext'
import { AppLayout } from '../components/layout/AppLayout'
import { NotYetAvailable } from '../components/shared/NotYetAvailable'
// Config pages
import { ConfigLayout } from './config/ConfigLayout'
import { ConfigGeneralPage } from './config/ConfigGeneralPage'
import { ConfigTransactionPage } from './config/ConfigTransactionPage'
import { ConfigGaugeListPage } from './config/ConfigGaugeListPage'
import { ConfigGaugePage } from './config/ConfigGaugePage'
import { ConfigJvmPage } from './config/ConfigJvmPage'
import { ConfigSyntheticMonitorListPage } from './config/ConfigSyntheticMonitorListPage'
import { ConfigSyntheticMonitorPage } from './config/ConfigSyntheticMonitorPage'
import { ConfigAlertListPage } from './config/ConfigAlertListPage'
import { ConfigAlertPage } from './config/ConfigAlertPage'
import { ConfigUiDefaultsPage } from './config/ConfigUiDefaultsPage'
import { ConfigPluginListPage } from './config/ConfigPluginListPage'
import { ConfigPluginPage } from './config/ConfigPluginPage'
import { ConfigInstrumentationListPage } from './config/ConfigInstrumentationListPage'
import { ConfigInstrumentationPage } from './config/ConfigInstrumentationPage'
import { ConfigAdvancedPage } from './config/ConfigAdvancedPage'
import { ConfigJsonPage } from './config/ConfigJsonPage'
// Admin pages
import { AdminLayout } from './admin/AdminLayout'
import { AdminGeneralPage } from './admin/AdminGeneralPage'
import { AdminWebPage } from './admin/AdminWebPage'
import { AdminStoragePage } from './admin/AdminStoragePage'
import { AdminSmtpPage } from './admin/AdminSmtpPage'
import { AdminHttpProxyPage } from './admin/AdminHttpProxyPage'
import { AdminIntegrationListPage } from './admin/AdminIntegrationListPage'
import { AdminLdapPage } from './admin/AdminLdapPage'
import { AdminPagerDutyPage } from './admin/AdminPagerDutyPage'
import { AdminSlackPage } from './admin/AdminSlackPage'
import { AdminHealthchecksIoPage } from './admin/AdminHealthchecksIoPage'
import { AdminUserListPage } from './admin/AdminUserListPage'
import { AdminUserPage } from './admin/AdminUserPage'
import { AdminRoleListPage } from './admin/AdminRoleListPage'
import { AdminRolePage } from './admin/AdminRolePage'
import { AdminJsonPage } from './admin/AdminJsonPage'

export function AppRouter() {
  const basename = getContextPath() || undefined
  return (
    <BrowserRouter basename={basename}>
      <LayoutProvider>
        <Routes>
          <Route path="/modern" element={<AppLayout />}>
            {/* Default redirect */}
            <Route index element={<DefaultRedirect />} />

            {/* Transaction routes */}
            <Route path="transaction">
              <Route path="average" element={<NotYetAvailable />} />
              <Route path="percentiles" element={<NotYetAvailable />} />
              <Route path="throughput" element={<NotYetAvailable />} />
              <Route path="traces" element={<NotYetAvailable />} />
              <Route path="queries" element={<NotYetAvailable />} />
              <Route path="service-calls" element={<NotYetAvailable />} />
              <Route path="thread-profile" element={<NotYetAvailable />} />
              <Route path="thread-flame-graph" element={<NotYetAvailable />} />
              <Route path="trace-thread-flame-graph" element={<NotYetAvailable />} />
            </Route>

            {/* Error routes */}
            <Route path="error">
              <Route path="messages" element={<NotYetAvailable />} />
              <Route path="traces" element={<NotYetAvailable />} />
            </Route>

            {/* JVM routes */}
            <Route path="jvm">
              <Route path="gauges" element={<NotYetAvailable />} />
              <Route path="thread-dump" element={<NotYetAvailable />} />
              <Route path="jstack" element={<NotYetAvailable />} />
              <Route path="heap-dump" element={<NotYetAvailable />} />
              <Route path="heap-histogram" element={<NotYetAvailable />} />
              <Route path="force-gc" element={<NotYetAvailable />} />
              <Route path="mbean-tree" element={<NotYetAvailable />} />
              <Route path="system-properties" element={<NotYetAvailable />} />
              <Route path="environment" element={<NotYetAvailable />} />
              <Route path="capabilities" element={<NotYetAvailable />} />
            </Route>

            {/* Synthetic monitors (central only) */}
            <Route path="synthetic-monitors" element={<NotYetAvailable />} />

            {/* Incidents */}
            <Route path="incidents" element={<NotYetAvailable />} />

            {/* Reports */}
            <Route path="report">
              <Route path="ad-hoc" element={<NotYetAvailable />} />
            </Route>

            {/* Config routes */}
            <Route path="config" element={<ConfigLayout />}>
              <Route path="general" element={<ConfigGeneralPage />} />
              <Route path="transaction" element={<ConfigTransactionPage />} />
              <Route path="gauge-list" element={<ConfigGaugeListPage />} />
              <Route path="gauge" element={<ConfigGaugePage />} />
              <Route path="jvm" element={<ConfigJvmPage />} />
              <Route path="synthetic-monitor-list" element={<ConfigSyntheticMonitorListPage />} />
              <Route path="synthetic-monitor" element={<ConfigSyntheticMonitorPage />} />
              <Route path="alert-list" element={<ConfigAlertListPage />} />
              <Route path="alert" element={<ConfigAlertPage />} />
              <Route path="ui-defaults" element={<ConfigUiDefaultsPage />} />
              <Route path="plugin-list" element={<ConfigPluginListPage />} />
              <Route path="plugin" element={<ConfigPluginPage />} />
              <Route path="instrumentation-list" element={<ConfigInstrumentationListPage />} />
              <Route path="instrumentation" element={<ConfigInstrumentationPage />} />
              <Route path="advanced" element={<ConfigAdvancedPage />} />
              <Route path="json" element={<ConfigJsonPage />} />
            </Route>

            {/* Admin routes */}
            <Route path="admin" element={<AdminLayout />}>
              <Route path="general" element={<AdminGeneralPage />} />
              <Route path="user-list" element={<AdminUserListPage />} />
              <Route path="user" element={<AdminUserPage />} />
              <Route path="role-list" element={<AdminRoleListPage />} />
              <Route path="role" element={<AdminRolePage />} />
              <Route path="web" element={<AdminWebPage />} />
              <Route path="storage" element={<AdminStoragePage />} />
              <Route path="smtp" element={<AdminSmtpPage />} />
              <Route path="http-proxy" element={<AdminHttpProxyPage />} />
              <Route path="integration-list" element={<AdminIntegrationListPage />} />
              <Route path="integration/ldap" element={<AdminLdapPage />} />
              <Route path="integration/pager-duty" element={<AdminPagerDutyPage />} />
              <Route path="integration/slack" element={<AdminSlackPage />} />
              <Route path="integration/healthchecks-io" element={<AdminHealthchecksIoPage />} />
              <Route path="json" element={<AdminJsonPage />} />
            </Route>

            {/* Profile routes */}
            <Route path="profile">
              <Route path="change-password" element={<NotYetAvailable />} />
            </Route>

            {/* Login */}
            <Route path="login" element={<NotYetAvailable />} />

            {/* Catch-all */}
            <Route path="*" element={<NotYetAvailable />} />
          </Route>

          {/* Redirect bare /modern to /modern/ */}
          <Route path="/" element={<Navigate to="/modern/" replace />} />
        </Routes>
      </LayoutProvider>
    </BrowserRouter>
  )
}

/**
 * Default redirect logic matching the AngularJS $urlRouterProvider.otherwise.
 * Redirects to the first section the user has access to.
 */
function DefaultRedirect() {
  const { layout } = useLayout()
  const { agentRollup, isRollup } = useAgent()

  if (layout.showNavbarTransaction) {
    return <Navigate to="/modern/transaction/average" replace />
  }
  if (layout.showNavbarError) {
    return <Navigate to="/modern/error/messages" replace />
  }
  if (layout.showNavbarJvm) {
    const jvm = agentRollup?.permissions?.jvm
    if (jvm) {
      const isLeafAgent = !isRollup
      const isOnlineLeafAgent = isLeafAgent && !layout.offlineViewer
      if (jvm.gauges) {
        return <Navigate to="/modern/jvm/gauges" replace />
      }
      if (isOnlineLeafAgent && jvm.threadDump) {
        return <Navigate to="/modern/jvm/thread-dump" replace />
      }
      if (isOnlineLeafAgent && jvm.heapDump) {
        return <Navigate to="/modern/jvm/heap-dump" replace />
      }
      if (isOnlineLeafAgent && jvm.heapHistogram) {
        return <Navigate to="/modern/jvm/heap-histogram" replace />
      }
      if (isOnlineLeafAgent && jvm.forceGC) {
        return <Navigate to="/modern/jvm/force-gc" replace />
      }
      if (isOnlineLeafAgent && jvm.mbeanTree) {
        return <Navigate to="/modern/jvm/mbean-tree" replace />
      }
      if (isOnlineLeafAgent && jvm.systemProperties) {
        return <Navigate to="/modern/jvm/system-properties" replace />
      }
      if (isLeafAgent && jvm.environment) {
        return <Navigate to="/modern/jvm/environment" replace />
      }
    }
    return <Navigate to="/modern/jvm/gauges" replace />
  }
  if (layout.showNavbarConfig) {
    return (
      <Navigate
        to={layout.central ? '/modern/config/general' : '/modern/config/transaction'}
        replace
      />
    )
  }
  if (layout.adminView) {
    return (
      <Navigate
        to={layout.central ? '/modern/admin/user-list' : '/modern/admin/general'}
        replace
      />
    )
  }
  if (layout.loggedIn && !layout.ldap) {
    return <Navigate to="/modern/profile/change-password" replace />
  }
  return <Navigate to="/modern/transaction/average" replace />
}
