import { useEffect, useRef, useState } from 'react'

export const INDICATOR_DELAY_MS = 300
export const INDICATOR_MIN_VISIBLE_MS = 500

// 빠른 응답에서 로딩 표시가 나타났다 바로 사라지는 깜빡임을 막는다.
// 지연 안에 끝나면 표시하지 않고, 한 번 표시했으면 최소 시간을 채운 뒤에만 내린다.
export function useDelayedIndicator(active: boolean) {
  const [visible, setVisible] = useState(false)
  const shownAtRef = useRef(0)

  useEffect(() => {
    if (active) {
      if (visible) return
      const timer = setTimeout(() => {
        shownAtRef.current = Date.now()
        setVisible(true)
      }, INDICATOR_DELAY_MS)
      return () => clearTimeout(timer)
    }
    if (!visible) return
    const remaining = INDICATOR_MIN_VISIBLE_MS - (Date.now() - shownAtRef.current)
    const timer = setTimeout(() => setVisible(false), Math.max(0, remaining))
    return () => clearTimeout(timer)
  }, [active, visible])

  return visible
}
