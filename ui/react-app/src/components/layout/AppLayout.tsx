import { Outlet } from 'react-router-dom'
import { Navbar } from './Navbar'
import { Footer } from './Footer'
import { AgentProvider } from '../../contexts/AgentContext'
import { AuthProvider } from '../../contexts/AuthContext'

export function AppLayout() {
  return (
    <AuthProvider>
      <AgentProvider>
        <div className="flex min-h-screen flex-col bg-white text-[#444]">
          <Navbar />
          <main className="mx-auto w-full max-w-[1340px] 2xl:max-w-[1540px] flex-1 px-4 py-6">
            <Outlet />
          </main>
          <Footer />
        </div>
      </AgentProvider>
    </AuthProvider>
  )
}
