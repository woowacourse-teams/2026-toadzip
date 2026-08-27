import { createContext } from 'react'
import type { AdminSession } from './api'

export type AdminAuthContextValue = {
  session: AdminSession | null
  isLoading: boolean
  error: string | null
  login: (loginIdentifier: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

export const AdminAuthContext = createContext<AdminAuthContextValue | null>(null)
