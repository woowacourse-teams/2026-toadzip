import { createContext, useContext } from 'react'
import type { getCurrentUser } from '../auth/api'
import type { BookmarkItem, BookmarkType } from './bookmarkRepository'
export interface BookmarkContextValue {
  readonly mode: 'loading' | 'guest' | 'member' | 'error'
  readonly listStatus: 'loading' | 'ready' | 'error'
  readonly user: Awaited<ReturnType<typeof getCurrentUser>>
  readonly items: readonly BookmarkItem[]
  readonly pending: ReadonlySet<string>
  readonly error: string
  readonly reload: () => void
  readonly toggle: (type: BookmarkType, id: string) => Promise<void>
}
export const BookmarkContext = createContext<BookmarkContextValue | null>(null)
export const bookmarkKey = (type: BookmarkType, id: string) => `${type}:${id}`
export function useBookmarks() { return useContext(BookmarkContext) }
