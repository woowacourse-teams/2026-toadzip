import { Link } from 'react-router'
import { useRef, useState } from 'react'
import { MemberMenuModal } from '../../user/auth/MemberMenuModal'
import { BrandLink } from '../../BrandLink'
import { NotificationInbox } from '../../notifications/NotificationInbox'
import { useNotificationInterests } from './NotificationInterestContext'
import { NotificationSettings } from './NotificationSettings'
import styles from './NotificationPage.module.css'

export function NotificationPage({ management = false }: { readonly management?: boolean }) {
  return <NotificationPageContent management={management} />
}

function NotificationPageContent({ management }: { readonly management: boolean }) {
  const context = useNotificationInterests()
  const [inboxOpen, setInboxOpen] = useState(false)
  const inboxTrigger = useRef<HTMLButtonElement>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  const title = management ? '알림 관리' : '알림 보관함'
  return <div className={styles.page}>
    <header className={styles.header}><BrandLink /><Link to="/explore">지도로 돌아가기</Link></header>
    <main className={styles.content}>
      <p className={styles.eyebrow}>{management ? '마이페이지' : '나의 알림'}</p>
      <div className={styles.titleRow}><h1 ref={heading} tabIndex={-1}>{title}</h1>
        {management && context?.mode === 'member' && <button ref={inboxTrigger} className={styles.inboxButton} type="button" aria-haspopup="dialog" onClick={() => setInboxOpen(true)}>알림 보관함</button>}
      </div>
      {context?.mode === 'loading' && <p role="status">알림 설정을 불러오는 중…</p>}
      {context?.mode === 'guest' && <section className={styles.notice}>
        <h2>로그인이 필요해요</h2><p>로그인한 사용자만 이용할 수 있어요.</p>
        <Link to="/explore?login=required">로그인</Link>
      </section>}
      {context?.mode === 'member' && (management ? <NotificationSettings /> : <NotificationInbox />)}
      {context?.mode === 'error' && <p role="alert">로그인 상태를 확인하지 못했어요. <button onClick={context.refreshStatus}>다시 시도</button></p>}
    </main>
    {inboxOpen && context?.mode === 'member' && <MemberMenuModal view="inbox" onClose={() => setInboxOpen(false)} returnFocusRef={inboxTrigger} />}
  </div>
}
