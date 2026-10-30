import { useExplorerReturn } from '../../public-housing/navigation/ExplorerReturnContext'
import { useLocation, useNavigate } from 'react-router'
import { useMobileViewport } from '../../public-housing/components/useMobileViewport'
import { loginPageUrl, rememberLoginReturn } from '../auth/loginReturn'
import { useRef, useState } from 'react'
import { LoginModal } from '../auth/LoginModal'
import { bookmarkKey, useBookmarks, type BookmarkContextValue } from './BookmarkContext'
import type { BookmarkType } from './bookmarkRepository'
import styles from './BookmarkButton.module.css'
interface Props { readonly type: BookmarkType; readonly targetId: string; readonly name: string; readonly iconOnly?: boolean; readonly size?: 'sm' | 'md' }
export function BookmarkButton(props: Props) {
  const context = useBookmarks()
  return context ? <BookmarkControl {...props} context={context} /> : null
}
function BookmarkControl({ type, targetId, name, iconOnly = false, size = 'sm', context }: Props & { readonly context: BookmarkContextValue }) {
  const location = useLocation()
  const explorerReturn = useExplorerReturn()
  const navigate = useNavigate()
  const mobile = useMobileViewport()
  const [login, setLogin] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  const saved = context.optimistic.get(bookmarkKey(type, targetId)) ?? context.items.some((item) => item.type === type && item.targetId === targetId)
  const pending = context.pending.has(bookmarkKey(type, targetId))
  return <span className={`${styles.wrapper}${iconOnly && size === 'md' ? ` ${styles.iconWrapper}` : ''}`}>
    <button ref={trigger} type="button" className={iconOnly ? size === 'md' ? `${styles.button} ${styles.iconButton}` : styles.icon : styles.button} aria-label={`${name} 북마크 ${saved ? '해제' : '저장'}`} title={iconOnly ? `북마크 ${saved ? '해제' : '저장'}` : undefined}
      aria-pressed={saved} aria-busy={pending || undefined} disabled={context.mode === 'loading' || context.mode === 'member' && context.listStatus === 'loading' || pending}
      onClick={() => { if (context.mode === 'guest') { if (mobile) { const returnTo = explorerReturn?.current ?? location.pathname + location.search + location.hash; rememberLoginReturn(returnTo); void navigate(loginPageUrl(returnTo)) } else setLogin(true) } else if (context.mode === 'error' || context.listStatus === 'error') context.reload(); else void context.toggle(type, targetId) }}>
      <svg aria-hidden="true" viewBox="0 0 24 24" fill={saved ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.7"><path d="M6 3h12v18l-6-4-6 4z" /></svg>
      {!iconOnly && (context.mode === 'error' || context.listStatus === 'error' ? '북마크 다시 확인' : saved ? '저장됨' : '북마크')}
    </button>
    {context.error && <span className={styles.error} role="alert">{context.error}</span>}
    {login && <LoginModal loginFailed={false} sessionError={false} onClose={() => setLogin(false)} returnFocusRef={trigger} />}
  </span>
}
