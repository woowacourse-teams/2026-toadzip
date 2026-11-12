import { useLayoutEffect, useRef } from 'react'
import { Button } from '../design-system/components/Button'
import { IconButton } from '../design-system/components/IconButton'
import { useMobileInstall } from './MobileInstallContext'
import styles from './MobileInstall.module.css'

export function MobileInstallCard({ floating = false }: { readonly floating?: boolean }) {
  const install = useMobileInstall()
  if (!install?.visible || install.dismissed) return null
  return <InstallCard install={install} floating={floating} />
}

const PROMPT_BOTTOM = '--floating-prompt-bottom'
const PROMPT_GAP = 8

/** 지도 위에 뜬 설치 카드의 윗변을 알려 의견 요청 같은 다른 안내가 그 위에 뜨게 한다. */
function useFloatingOffset(ref: React.RefObject<HTMLElement | null>, enabled: boolean) {
  useLayoutEffect(() => {
    const element = ref.current
    if (!enabled || !element) return
    const root = document.documentElement
    const update = () => root.style.setProperty(PROMPT_BOTTOM, `${Math.round(window.innerHeight - element.getBoundingClientRect().top + PROMPT_GAP)}px`)
    update()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update)
    observer?.observe(element)
    window.addEventListener('resize', update)
    return () => {
      observer?.disconnect()
      window.removeEventListener('resize', update)
      root.style.removeProperty(PROMPT_BOTTOM)
    }
  }, [ref, enabled])
}

function InstallCard({ install, floating }: { readonly install: NonNullable<ReturnType<typeof useMobileInstall>>; readonly floating: boolean }) {
  const card = useRef<HTMLElement>(null)
  useFloatingOffset(card, floating)
  return <aside ref={card} className={`${styles.card} ${floating ? styles.floating : ''}`} aria-label="복덕방 홈 화면 추가">
    <img className={styles.logo} src="/apple-touch-icon.png" width="40" height="40" alt="" />
    <div className={styles.copy}><strong>복덕방을 앱처럼 편하게 열어보세요</strong>
      <Button className={styles.add} disabled={install.busy} onClick={install.open}>{install.busy ? '설치창 여는 중…' : install.platform === 'android' && install.canPrompt ? '복덕방 설치하기' : '앱처럼 사용하기'}</Button>
      {install.message && <span role="status">{install.message}</span>}
    </div>
    <IconButton label="홈 화면 추가 안내 닫기" onClick={install.dismiss}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6" /></svg></IconButton>
  </aside>
}

export function MobileInstallAction() {
  const install = useMobileInstall()
  if (!install?.visible) return null
  return <div className={styles.action}>
    <button type="button" disabled={install.busy} onClick={install.open}>{install.platform === 'android' && install.canPrompt ? '복덕방 설치하기' : '홈 화면에 추가'} <span aria-hidden="true">↗</span></button>
    {install.message && <p role="status">{install.message}</p>}
  </div>
}
