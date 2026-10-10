import { useRef, type KeyboardEvent } from 'react'

/** Reuses the live result list as suggestions, without making a second API request. */
export function useSearchSuggestionsKeyboard(onClear: () => void) {
  const inputRef = useRef<HTMLInputElement>(null)
  const suggestionsRef = useRef<HTMLDivElement>(null)

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) return
    const input = inputRef.current
    const buttons = Array.from(suggestionsRef.current?.querySelectorAll<HTMLButtonElement>(
      'button[data-search-suggestion]:not(:disabled)',
    ) ?? [])
    const current = buttons.indexOf(event.target as HTMLButtonElement)
    const fromInput = event.target === input
    if (!fromInput && current < 0) return

    if (event.key === 'Escape' && input?.value) {
      event.preventDefault()
      event.stopPropagation()
      onClear()
      input.focus({ preventScroll: true })
      return
    }
    if (buttons.length === 0) return
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const next = fromInput
        ? (event.key === 'ArrowDown' ? 0 : buttons.length - 1)
        : current + (event.key === 'ArrowDown' ? 1 : -1)
      if (next < 0 || next >= buttons.length) input?.focus({ preventScroll: true })
      else buttons[next].focus()
    } else if (event.key === 'Enter' && fromInput) {
      event.preventDefault()
      buttons[0].click()
    }
  }

  return { inputRef, suggestionsRef, onKeyDown }
}
