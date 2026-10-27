import { createContext, useContext } from 'react'

export type InstallPlatform = 'ios' | 'android'
export interface MobileInstallState {
  readonly visible: boolean
  readonly dismissed: boolean
  readonly canPrompt: boolean
  readonly busy: boolean
  readonly message: string
  readonly platform: InstallPlatform
  readonly open: () => void
  readonly dismiss: () => void
}
export const MobileInstallContext = createContext<MobileInstallState | null>(null)
export function useMobileInstall() { return useContext(MobileInstallContext) }
