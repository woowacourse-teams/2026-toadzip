import { createContext } from 'react'
import type { AdminSession } from './api'

export type AdminAuthContextValue = {
  session: AdminSession | null
  isLoading: boolean
  error: string | null
  // 사이트에 소셜 로그인했지만 관리자 권한이 없는 계정이다.
  forbidden: boolean
  login: (loginIdentifier: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

export const AdminAuthContext = createContext<AdminAuthContextValue | null>(null)
