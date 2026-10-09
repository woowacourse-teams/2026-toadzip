import { useEffect } from 'react'

const confirmedEvents = new WeakSet<Event>()

export function useUnsavedChanges(active: boolean) {
  useEffect(() => {
    if (!active) return
    const unload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    const navigate = (event: MouseEvent) => {
      if (event.defaultPrevented || confirmedEvents.has(event) || !(event.target instanceof Element)) return
      const target = event.target.closest('a[href], button')
      if (!target || (target instanceof HTMLAnchorElement &&
        (target.target === '_blank' || target.origin !== location.origin || target.hash && target.pathname === location.pathname))) return
      if (target instanceof HTMLButtonElement && (target.type === 'submit' || !target.closest('[data-admin-navigation]'))) return
      confirmedEvents.add(event)
      if (!window.confirm('저장하지 않은 변경사항을 버리고 이동할까요?')) {
        event.preventDefault()
        event.stopPropagation()
      }
    }
    const submit = (event: SubmitEvent) => {
      if (event.defaultPrevented || confirmedEvents.has(event) || !(event.target instanceof Element) || !event.target.closest('[data-admin-navigation]')) return
      confirmedEvents.add(event)
      if (!window.confirm('저장하지 않은 변경사항을 버리고 이동할까요?')) {
        event.preventDefault()
        event.stopPropagation()
      }
    }
    window.addEventListener('beforeunload', unload)
    document.addEventListener('click', navigate, true)
    document.addEventListener('submit', submit, true)
    return () => {
      window.removeEventListener('beforeunload', unload)
      document.removeEventListener('click', navigate, true)
      document.removeEventListener('submit', submit, true)
    }
  }, [active])
}
