import { Link } from 'react-router'
import { useRef } from 'react'
import { BrandLink } from '../../BrandLink'
import { NotificationInterestProvider } from './NotificationInterest'
import { notificationPreparationDescription, notificationPreparationNotice, notificationPreparationTitle, useNotificationInterests } from './NotificationInterestContext'
import type { NotificationTargetType } from './notificationInterestRepository'
import styles from './NotificationPage.module.css'

const groups: readonly { type: NotificationTargetType; title: string }[] = [
  { type: 'COMPLEX', title: '단지' }, { type: 'ANNOUNCEMENT', title: '공고' }, { type: 'REGION', title: '지역' },
]

export function NotificationPage({ management = false }: { readonly management?: boolean }) {
  return <NotificationInterestProvider><NotificationPageContent management={management} /></NotificationInterestProvider>
}

function NotificationPageContent({ management }: { readonly management: boolean }) {
  const context = useNotificationInterests()
  const heading = useRef<HTMLHeadingElement>(null)
  const title = management ? '알림 관리' : '알림 보관함'
  return <div className={styles.page}>
    <header className={styles.header}><BrandLink /><Link to="/">지도로 돌아가기</Link></header>
    <main className={styles.content}>
      <p className={styles.eyebrow}>{management ? '마이페이지' : '나의 알림'}</p>
      <div className={styles.titleRow}><h1 ref={heading} tabIndex={-1}>{title}</h1>
        {context?.mode === 'member' && <Link to={management ? '/notifications' : '/mypage/notifications'}>
          {management ? '알림 보관함' : '알림 관리'}</Link>}
      </div>
      {context?.mode === 'loading' && <p role="status">알림 설정을 불러오는 중…</p>}
      {context?.mode === 'guest' && <section className={styles.notice}>
        <h2>로그인이 필요해요</h2><p>로그인한 사용자만 이용할 수 있어요.</p>
        <Link to="/?login=required">로그인</Link>
      </section>}
      {context?.mode === 'member' && <>
        <section className={styles.notice}>
          <h2>{notificationPreparationTitle}</h2>
          <p>{notificationPreparationDescription}</p><p>{notificationPreparationNotice}</p>
          {management && <p className={styles.savedNotice}>알림 설정은 저장돼요. 아직 알림이 전달되지는 않아요.</p>}
        </section>
        {management && <>
          <div className={styles.managementActions}>
            <p className={styles.intro}>알림을 설정한 단지·공고·지역을 확인하고 해제할 수 있어요.</p>
            <button className={styles.clearAll} type="button" disabled={context.blocked || context.targets.length === 0}
              onClick={(event) => { heading.current?.focus(); context.clearAll(event.currentTarget) }}>
              {context.clearingAll ? '전체 해제 중…' : '전체 해제'}
            </button>
          </div>
          {groups.map(({ type, title: groupTitle }) => {
            const targets = context.targets.filter((target) => target.type === type)
            return <section className={styles.group} key={type} aria-label={`${groupTitle} 알림 설정`}>
              <h2>{groupTitle} <span>{targets.length}</span></h2>
              {targets.length === 0 ? <p className={styles.empty}>알림을 설정한 {groupTitle === '지역' ? '지역이' : `${groupTitle}가`} 없어요.</p>
                : <ul>{targets.map((target) => <li key={target.id}>
                  <span>{target.name}</span>
                  <button type="button" disabled={context.blocked} aria-label={`${target.name} 알림 해제`}
                    onClick={(event) => { heading.current?.focus(); context.request({ target, source: 'SETTING' }, event.currentTarget) }}>해제</button>
                </li>)}</ul>}
            </section>
          })}
          <p className={styles.retention}>알림 설정은 저장한 날부터 12개월 동안 유지돼요.</p>
        </>}
      </>}
    </main>
  </div>
}
