import { useEffect, useState, type ReactNode } from 'react'
import { AdminApiError, getCurrentAdmin, loginAdmin, logoutAdmin, type AdminSession } from './api'
import { AdminAuthContext } from './AdminAuthContext'

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<AdminSession | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void restoreSession()
  }, [])

  async function restoreSession() {
    try {
      setSession(await getCurrentAdmin())
    } catch (requestError) {
      if (!(requestError instanceof AdminApiError) || requestError.status !== 401) {
        setError('관리자 인증 상태를 확인하지 못했습니다.')
      }
    } finally {
      setIsLoading(false)
    }
  }

  async function login(loginIdentifier: string, password: string) {
    setError(null)
    const authenticatedSession = await loginAdmin(loginIdentifier, password)
    setSession(authenticatedSession)
  }

  async function logout() {
    await logoutAdmin()
    setSession(null)
  }

  return (
    <AdminAuthContext.Provider value={{ session, isLoading, error, login, logout }}>
      {children}
    </AdminAuthContext.Provider>
  )
}
