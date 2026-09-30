import { useEffect } from 'react'

export function useUnsavedChanges(active: boolean) {
  useEffect(() => {
    if (!active) return
    const unload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    const navigate = (event: MouseEvent) => {
      if (event.defaultPrevented || !(event.target instanceof Element)) return
      const target = event.target.closest('a[href], [data-admin-navigation]')
      if (!target || (target instanceof HTMLAnchorElement &&
        (target.target === '_blank' || target.origin !== location.origin || target.hash && target.pathname === location.pathname))) return
      if (!window.confirm('저장하지 않은 변경사항을 버리고 이동할까요?')) {
        event.preventDefault()
        event.stopPropagation()
      }
    }
    window.addEventListener('beforeunload', unload)
    document.addEventListener('click', navigate, true)
    return () => {
      window.removeEventListener('beforeunload', unload)
      document.removeEventListener('click', navigate, true)
    }
  }, [active])
}
