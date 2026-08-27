import { useEffect, useRef, useState, type ReactNode } from 'react'
import { AdminApiError, getCurrentAdmin, loginAdmin, logoutAdmin, type AdminSession } from './api'
import { AdminAuthContext } from './AdminAuthContext'

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<AdminSession | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const sessionStateGeneration = useRef(0)

  useEffect(() => {
    void restoreSession()
  }, [])

  async function restoreSession() {
    const generation = nextSessionStateGeneration()
    try {
      const restoredSession = await getCurrentAdmin()
      if (isLatestSessionStateGeneration(generation)) {
        setSession(restoredSession)
      }
    } catch (requestError) {
      if (
        isLatestSessionStateGeneration(generation)
        && (!(requestError instanceof AdminApiError) || requestError.status !== 401)
      ) {
        setError('관리자 인증 상태를 확인하지 못했습니다.')
      }
    } finally {
      if (isLatestSessionStateGeneration(generation)) {
        setIsLoading(false)
      }
    }
  }

  async function login(loginIdentifier: string, password: string) {
    setError(null)
    const generation = nextSessionStateGeneration()
    const authenticatedSession = await loginAdmin(loginIdentifier, password)
    if (isLatestSessionStateGeneration(generation)) {
      setSession(authenticatedSession)
    }
  }

  async function logout() {
    const generation = nextSessionStateGeneration()
    try {
      await logoutAdmin()
    } catch (requestError) {
      if (!(requestError instanceof AdminApiError) || requestError.status !== 401) {
        throw requestError
      }
    }
    if (isLatestSessionStateGeneration(generation)) {
      setSession(null)
    }
  }

  function nextSessionStateGeneration(): number {
    sessionStateGeneration.current += 1
    return sessionStateGeneration.current
  }

  function isLatestSessionStateGeneration(generation: number): boolean {
    return generation === sessionStateGeneration.current
  }

  return (
    <AdminAuthContext.Provider value={{ session, isLoading, error, login, logout }}>
      {children}
    </AdminAuthContext.Provider>
  )
}
