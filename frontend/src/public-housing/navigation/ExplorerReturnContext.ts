import { createContext, useContext, type RefObject } from 'react'

export const ExplorerReturnContext = createContext<RefObject<string | null> | null>(null)

export function useExplorerReturn() {
  return useContext(ExplorerReturnContext)
}
