import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { HousingComplexRegistrationPage } from '../registration/HousingComplexRegistrationPage'
import { AnnouncementRegistrationPage } from '../registration/AnnouncementRegistrationPage'
import type { ManagementResource } from './managementContract'
import { ManagementDetail } from './ManagementDetail'
import { ManagementList } from './ManagementList'
import { managementListParams, managementUrl } from './managementNavigation'
import { workReturnTo } from '../shared/workNavigation'
import styles from './ManagementWorkspace.module.css'

export function ManagementWorkspace({ resource }: { resource: ManagementResource }) {
  const { id } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [revision, setRevision] = useState(0)
  const [registrationSession, setRegistrationSession] = useState(0)
  const [showList, setShowList] = useState(false)
  const workspaceRef = useRef<HTMLElement>(null)
  const editorRef = useRef<HTMLElement>(null)
  const addRef = useRef<HTMLAnchorElement>(null)
  const returnFocusRef = useRef<HTMLAnchorElement | null>(null)
  const listParams = managementListParams(params, resource)
  const editorSession = useMemo(() => ({ resource, id, registrationSession }), [resource, id, registrationSession])
  const currentEditor = useRef<{ session: typeof editorSession; listParams: URLSearchParams } | null>(null)
  const back = workReturnTo(params) ?? managementUrl(resource, listParams)
  const title = resource === 'complexes' ? '단지' : '공고'

  useEffect(() => {
    if (id) {
      returnFocusRef.current = id === 'new' ? addRef.current
        : /^\d+$/.test(id) ? workspaceRef.current?.querySelector<HTMLAnchorElement>(`a[data-management-id="${id}"]`) ?? null : null
      editorRef.current?.focus()
      editorRef.current?.scrollIntoView?.({ block: 'start' })
      return
    }
    if (returnFocusRef.current?.isConnected) returnFocusRef.current.focus()
    returnFocusRef.current = null
  }, [id, resource])

  useEffect(() => {
    currentEditor.current = { session: editorSession, listParams: managementListParams(params, resource) }
    return () => { currentEditor.current = null }
  }, [editorSession, params, resource])

  function created(createdId: number) {
    const current = currentEditor.current
    if (!current || current.session.resource !== resource) return
    setRevision(value => value + 1)
    if (current.session === editorSession) {
      navigate(managementUrl(resource, current.listParams, String(createdId)), { replace: true })
    }
  }

  return <section ref={workspaceRef} className={styles.workspace}>
    {/* 상세를 열면 제목·목록 복귀·목록 펼치기·추가를 한 줄에 둔다. 상세 안의 별도 닫기 줄은 같은 일을 해서 두지 않는다. */}
    <header className={styles.heading} data-detail={Boolean(id)}>
      <div className={styles.headingTitle}>
        <h1>{title} 관리</h1>
        {id && <Link className={styles.back} to={back}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M15 6l-6 6 6 6" /></svg>
          목록으로 돌아가기
        </Link>}
      </div>
      <div className="admin-inline">
        {id && <button type="button" aria-expanded={showList} onClick={() => setShowList(current => !current)}>{showList ? '목록과 검색 접기' : '목록과 검색 펼치기'}</button>}
        <Link ref={addRef} className={id ? 'admin-tonal' : 'admin-primary'} to={managementUrl(resource, listParams, 'new')} onClick={() => setRegistrationSession(value => value + 1)}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
          {title} 추가
        </Link>
      </div>
    </header>
    {!id && <p className={styles.description}>{title}를 선택해 정보를 확인하거나 공식 자료로 보완하세요.</p>}
    <div className={`${styles.body} ${id ? styles.withEditor : ''}`}>
      <div className={styles.listPane} hidden={Boolean(id) && !showList}><ManagementList resource={resource} embedded compact={false} refresh={revision} /></div>
      {id ? <section ref={editorRef} tabIndex={-1} className={styles.editor} aria-label={`${title} ${id === 'new' ? '추가' : '상세·수정'}`}>
        <div className={styles.editorBody}>{id === 'new' ? resource === 'complexes'
          ? <HousingComplexRegistrationPage key={registrationSession} embedded onCreated={created} />
          : <AnnouncementRegistrationPage key={registrationSession} embedded onCreated={created} />
          : <ManagementDetail key={`${resource}-${id}`} resource={resource} embedded onChanged={() => setRevision(value => value + 1)} />}</div>
      </section> : null}
    </div>
  </section>
}
