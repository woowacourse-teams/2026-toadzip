import { useContext } from 'react'
import { AdminAuthContext } from './AdminAuthContext'

export function useAdminAuth() {
  const context = useContext(AdminAuthContext)
  if (!context) {
    throw new Error('AdminAuthProvider 안에서만 useAdminAuth를 사용할 수 있습니다.')
  }
  return context
}
