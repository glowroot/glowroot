/*
 * AuthProvider — login/signOut, 401 redirect handling.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useLayout } from './LayoutContext'
import { apiPost, setOnLoginRedirect } from '../lib/api'

interface AuthContextValue {
  loggedIn: boolean
  loginEnabled: boolean
  ldap: boolean
  /** Message shown on the login page (e.g. "Your session has timed out") */
  loginMessage: string | null
  /** Transient "Signed out" banner flag */
  signOutMessage: boolean
  signOut: () => Promise<void>
  login: (username: string, password: string) => Promise<{ incorrectLogin?: boolean }>
  clearLoginMessage: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const { layout, refreshLayout, setNavbarErrorMessage } = useLayout()
  const navigate = useNavigate()
  const location = useLocation()
  const [loginMessage, setLoginMessage] = useState<string | null>(null)
  const [returnPath, setReturnPath] = useState<string | null>(null)
  const [signOutMessage, setSignOutMessage] = useState(false)
  const signOutTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    return () => {
      if (signOutTimerRef.current) {
        clearTimeout(signOutTimerRef.current)
      }
    }
  }, [])

  // Wire the api module's 401 redirect to us
  useEffect(() => {
    setOnLoginRedirect((timedOut: boolean) => {
      if (location.pathname === '/modern/login') return
      setReturnPath(location.pathname + location.search)
      setLoginMessage(timedOut ? 'Your session has timed out' : null)
      navigate('/modern/login')
    })
  }, [navigate, location])

  // Honor layout.redirectToLogin and redirect away from /modern/login if auth is not needed
  useEffect(() => {
    if (layout.redirectToLogin && location.pathname !== '/modern/login') {
      setReturnPath(location.pathname + location.search)
      navigate('/modern/login', { replace: true })
    } else if (
      location.pathname === '/modern/login' &&
      (layout.loggedIn || !layout.loginEnabled) &&
      !layout.redirectToLogin
    ) {
      const target = returnPath && returnPath !== '/modern/login' ? returnPath : '/modern/'
      setReturnPath(null)
      navigate(target, { replace: true })
    }
  }, [
    layout.redirectToLogin,
    layout.loggedIn,
    layout.loginEnabled,
    location.pathname,
    location.search,
    returnPath,
    navigate,
  ])

  const login = useCallback(
    async (username: string, password: string): Promise<{ incorrectLogin?: boolean }> => {
      const resp = await apiPost<{ incorrectLogin?: boolean }>('/backend/login', {
        username,
        password,
      })
      if (resp?.incorrectLogin) {
        return { incorrectLogin: true }
      }
      const target = returnPath && returnPath !== '/modern/login' ? returnPath : '/modern/'
      setLoginMessage(null)
      setReturnPath(null)
      await refreshLayout()
      navigate(target)
      return { incorrectLogin: false }
    },
    [refreshLayout, navigate, returnPath]
  )

  const signOut = useCallback(async () => {
    try {
      await apiPost('/backend/sign-out')
      await refreshLayout()
      setSignOutMessage(true)
      if (signOutTimerRef.current) clearTimeout(signOutTimerRef.current)
      signOutTimerRef.current = setTimeout(() => setSignOutMessage(false), 2000)
    } catch (e) {
      const msg = e instanceof Error && e.message ? `: ${e.message}` : ''
      setNavbarErrorMessage('An error occurred signing out' + msg)
    }
  }, [refreshLayout, setNavbarErrorMessage])

  const clearLoginMessage = useCallback(() => {
    setLoginMessage(null)
  }, [])

  return (
    <AuthContext.Provider
      value={{
        loggedIn: layout.loggedIn,
        loginEnabled: layout.loginEnabled,
        ldap: layout.ldap,
        loginMessage,
        signOutMessage,
        signOut,
        login,
        clearLoginMessage,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}
