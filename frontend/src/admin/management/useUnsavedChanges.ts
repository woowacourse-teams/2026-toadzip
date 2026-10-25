import { useEffect } from 'react'

export const unsavedChangesMessage = '저장하지 않은 변경사항을 버리고 이동할까요?'
const confirmedEvents = new WeakSet<Event>()
// 관리자 화면에 확인 창이 붙어 있으면 그 창으로 묻는다. 없으면(단독 렌더링) 브라우저 확인창으로 대신한다.
let askToLeave: ((leave: () => void) => void) | null = null
let leaving = false

export function connectUnsavedChangesDialog(ask: (leave: () => void) => void) {
  askToLeave = ask
  return () => { if (askToLeave === ask) askToLeave = null }
}

/** 이동을 멈추고 확인을 받는다. 확인하면 같은 동작을 다시 실행한다. */
function guard(event: Event, retry: () => void) {
  confirmedEvents.add(event)
  if (!askToLeave) {
    if (!window.confirm(unsavedChangesMessage)) { event.preventDefault(); event.stopPropagation() }
    return
  }
  event.preventDefault()
  event.stopPropagation()
  askToLeave(() => {
    leaving = true
    try { retry() } finally { leaving = false }
  })
}

export function useUnsavedChanges(active: boolean) {
  useEffect(() => {
    if (!active) return
    const unload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    const navigate = (event: MouseEvent) => {
      if (leaving || event.defaultPrevented || confirmedEvents.has(event) || !(event.target instanceof Element)) return
      const target = event.target.closest('a[href], button')
      if (!target || (target instanceof HTMLAnchorElement &&
        (target.target === '_blank' || target.origin !== location.origin || target.hash && target.pathname === location.pathname
          || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey))) return
      if (target instanceof HTMLButtonElement && (target.type === 'submit' || !target.closest('[data-admin-navigation]'))) return
      guard(event, () => (target as HTMLElement).click())
    }
    const submit = (event: SubmitEvent) => {
      const form = event.target
      if (leaving || event.defaultPrevented || confirmedEvents.has(event) || !(form instanceof HTMLFormElement) || !form.closest('[data-admin-navigation]')) return
      guard(event, () => form.requestSubmit(event.submitter))
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
