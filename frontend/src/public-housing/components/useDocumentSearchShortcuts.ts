import { useEffect, type RefObject } from 'react'

interface DocumentSearchShortcuts {
  readonly root: RefObject<HTMLElement | null>
  readonly ready: boolean
  readonly searchOpen: boolean
  readonly openSearch: () => void
  readonly hideSearch: () => void
}

export function useDocumentSearchShortcuts({
  root, ready, searchOpen, openSearch, hideSearch,
}: DocumentSearchShortcuts) {
  useEffect(() => {
    const target = root.current?.closest('dialog') ?? root.current
    if (!target) return

    function handleKeyDown(event: Event) {
      if (!(event instanceof KeyboardEvent) || event.isComposing) return
      const findShortcut = (event.metaKey || event.ctrlKey)
        && !event.altKey && event.key.toLowerCase() === 'f'
      const outlineHandlesEscape = event.target instanceof Element
        && event.target.closest('[data-document-outline][data-outline-open="true"]')

      if (findShortcut && ready) {
        event.preventDefault()
        event.stopPropagation()
        openSearch()
      } else if (event.key === 'Escape' && searchOpen && !outlineHandlesEscape) {
        event.preventDefault()
        event.stopPropagation()
        hideSearch()
      }
    }

    target.addEventListener('keydown', handleKeyDown)
    return () => target.removeEventListener('keydown', handleKeyDown)
  }, [root, hideSearch, openSearch, ready, searchOpen])
}
