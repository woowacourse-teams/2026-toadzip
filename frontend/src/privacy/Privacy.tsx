import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { privacyApi, type NoticeDocument } from './api'
import { consentStore } from './consentStore'
import styles from './Privacy.module.css'
import { usePrivacyConsent, usePrivacyNotices } from './usePrivacy'

export function PrivacyBoundary() {
  const { pathname } = useLocation()
  const adminPage = pathname.startsWith('/admin')
  useEffect(() => {
    if (adminPage) { consentStore.transition(); return }
    return consentStore.start()
  }, [adminPage])
  const state = usePrivacyConsent()
  const needsChoice = state.context && (state.requiresChoice || ['UNSET', 'EXPIRED', 'RECONSENT_REQUIRED'].includes(state.context.consent.effectiveStatus))
  if (pathname !== '/' || !needsChoice || state.loading) return null
  return <aside className={styles.floating} aria-label="이용 정보 수집 선택"><AnalyticsChoice /></aside>
}
export function AnalyticsChoice({ settings = false }: { readonly settings?: boolean }) {
  const state = usePrivacyConsent()
  const { notices } = usePrivacyNotices()
  const [details, setDetails] = useState<NoticeDocument | null>(null)
  const [detailError, setDetailError] = useState(false)
  const context = state.context
  const notice = notices.find(item => item.key === 'ANALYTICS_NOTICE')
  const [expanded, setExpanded] = useState(false)
  useEffect(() => {
    if (!expanded || !notice) return
    let active = true
    privacyApi.document(notice.key, notice.version).then(value => { if (active) { setDetails(value); setDetailError(false) } }, () => { if (active) setDetailError(true) })
    return () => { active = false }
  }, [expanded, notice])
  const granted = context?.consent.effectiveStatus === 'GRANTED'
  const labels = { UNSET: '선택 전', GRANTED: '허용', DENIED: '동의하지 않음', WITHDRAWN: '철회', EXPIRED: '선택 기간 만료', RECONSENT_REQUIRED: '변경된 안내 확인 필요' }
  return <section className={`${styles.card} ph-no-capture`} aria-labelledby="analytics-choice-title">
    <header className={styles.heading}><h2 id="analytics-choice-title">서비스 개선을 위한 이용 정보 수집</h2><span>선택</span></header>
    <p className={styles.introduction}>더 나은 서비스 경험을 위해 화면 조회·기능 사용 정보를 통계적으로 분석합니다.<br /><strong>이름·이메일·회원 ID와 연결하지 않습니다.</strong></p>
    <p className={styles.small}>동의하지 않아도 서비스를 이용할 수 있습니다.</p>
    {state.requiresChoice && <p className={styles.small} role="status">이 브라우저에 오래 보관된 선택을 정리했어요. 현재 수집은 중지되어 있으니 다시 선택해 주세요.</p>}
    {settings && <p className={styles.scope} role="status">{context?.subject.kind === 'MEMBER' ? '회원 계정에 적용되는 설정' : '현재 브라우저의 비회원 설정'} · {context ? labels[context.consent.effectiveStatus] : '확인 중'}{state.pendingStop ? ' · 현재 브라우저 수집 중지' : ''}</p>}
    <button className={styles.disclosure} type="button" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>자세히 보기 <span aria-hidden="true">{expanded ? '⌃' : '⌄'}</span></button>
    {expanded && <div className={styles.details}>
      {details ? <NoticeContent content={details.content} /> : <p role={detailError ? 'alert' : 'status'}>{detailError ? '안내문을 불러오지 못했어요. 개인정보처리방침에서 확인해 주세요.' : '안내문을 불러오는 중…'}</p>}
      <Link to="/privacy">개인정보처리방침</Link>
    </div>}
    <div className={styles.actions}>
      <button type="button" disabled={state.saving || !context} onClick={() => void consentStore.choose(granted ? 'WITHDRAW' : 'DENY', settings ? 'SETTINGS' : 'FIRST_VISIT')}>{granted ? '동의 철회' : '동의하지 않음'}</button>
      <button type="button" className={styles.allow} disabled={state.saving || !context || !notice || notice.version !== context.requiredNoticeVersion} onClick={() => void consentStore.choose('GRANT', settings ? 'SETTINGS' : 'FIRST_VISIT')}>동의하기</button>
    </div>
    {state.error && <div className={styles.error} role="alert"><p>{state.error}</p><button type="button" disabled={state.saving} onClick={() => void consentStore.retry()}>다시 확인</button></div>}
    {state.saving && <p className={styles.small} role="status">선택을 저장하고 있어요.</p>}
    <p className={styles.footer}>정보 메뉴에서 언제든 변경할 수 있어요.</p>
  </section>
}
export function PrivacyMenu() {
  return <details className={styles.menu}><summary aria-label="정보"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><circle cx="12" cy="12" r="9" /><path d="M12 10v7m0-11v1" /></svg><span>정보</span></summary>
    <nav aria-label="정보 및 개인정보 설정"><Link to="/privacy">개인정보처리방침</Link><Link to="/privacy/settings">분석 설정</Link><a href="mailto:toadzip.official@gmail.com">개인정보 문의·삭제 요청</a></nav>
  </details>
}
export function PrivacySettingsPage() {
  return <main className={styles.page}><Link to="/">← 지도로 돌아가기</Link><h1>분석 설정</h1><AnalyticsChoice settings /><p className={styles.small}>로그인 중에는 회원 계정의 선택을 적용해요. 로그아웃하면 이 브라우저의 비회원 선택을 적용합니다.</p><Link to="/privacy">개인정보처리방침</Link></main>
}
export function NoticeContent({ content }: { readonly content: string }) {
  const inline = (text: string) => text.split(/(\*\*[^*\n]+\*\*)/).map((part, index) =>
    part.startsWith('**') && part.endsWith('**') ? <strong key={index}>{part.slice(2, -2)}</strong> : part)
  return <div className={styles.document}>{content.split(/\n\s*\n/).map((block, index) => {
    if (block.startsWith('### ')) return <h3 key={index}>{inline(block.slice(4))}</h3>
    if (block.startsWith('## ')) return <h2 key={index}>{inline(block.slice(3))}</h2>
    if (block.startsWith('# ')) return <h2 key={index}>{inline(block.slice(2))}</h2>
    return <p key={index}>{inline(block)}</p>
  })}</div>
}
export function PrivacyPolicyPage() {
  const { key, version } = useParams()
  const { notices, error: noticeError, retry } = usePrivacyNotices()
  const current = notices.find(item => item.key === 'PRIVACY_POLICY')
  const documentKey = key ?? current?.key
  const documentVersion = version ?? current?.version
  const [document, setDocument] = useState<NoticeDocument | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    if (!documentKey || !documentVersion) return
    let active = true
    setDocument(null); setError(false)
    privacyApi.document(documentKey, documentVersion).then(value => { if (active) setDocument(value) }, () => { if (active) setError(true) })
    return () => { active = false }
  }, [documentKey, documentVersion, notices])
  return <main className={styles.page}><Link to="/">← 지도로 돌아가기</Link><h1>개인정보처리방침</h1>
    {((noticeError && !key) || error) ? <div role="alert"><p>문서를 불러오지 못했어요. 주소를 확인하거나 다시 시도해 주세요.</p><button type="button" onClick={retry}>다시 시도</button></div> : document ? <><p className={styles.small}>시행일 {document.effectiveAt.slice(0, 10)} · {document.version}</p><NoticeContent content={document.content} /><Link to={`/privacy/${document.key}/${document.version}`}>이 버전의 고정 주소</Link></> : <p role="status">문서를 불러오는 중…</p>}
    <footer><p>공공주택 복덕방 운영팀</p><a href="mailto:toadzip.official@gmail.com">toadzip.official@gmail.com</a><p><Link to="/privacy/settings">분석 설정 변경</Link></p></footer>
  </main>
}
