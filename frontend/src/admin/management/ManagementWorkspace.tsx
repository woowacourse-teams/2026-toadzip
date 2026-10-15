import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { HousingComplexRegistrationPage } from '../registration/HousingComplexRegistrationPage'
import { AnnouncementRegistrationPage } from '../registration/AnnouncementRegistrationPage'
import type { ManagementResource } from './managementContract'
import { ManagementDetail } from './ManagementDetail'
import { ManagementList } from './ManagementList'
import { managementListParams, managementUrl } from './managementNavigation'
import styles from './ManagementWorkspace.module.css'

export function ManagementWorkspace({ resource }: { resource: ManagementResource }) {
  const { id } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [revision, setRevision] = useState(0)
  const [showList, setShowList] = useState(false)
  const workspaceRef = useRef<HTMLElement>(null)
  const editorRef = useRef<HTMLElement>(null)
  const addRef = useRef<HTMLAnchorElement>(null)
  const returnFocusRef = useRef<HTMLAnchorElement | null>(null)
  const listParams = managementListParams(params, resource)
  const editorSession = useMemo(() => ({ resource, id }), [resource, id])
  const currentEditor = useRef<{ session: typeof editorSession; listParams: URLSearchParams } | null>(null)
  const back = managementUrl(resource, listParams)
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
    <header className={styles.heading}>
      <h1>{title} 관리</h1>
      <Link ref={addRef} className="admin-primary" to={managementUrl(resource, listParams, 'new')}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
        {title} 추가
      </Link>
    </header>
    {id ? <div className={styles.contextBar}><Link to={back}>← 목록으로 돌아가기</Link><button type="button" aria-expanded={showList} onClick={() => setShowList(current => !current)}>{showList ? '목록과 검색 접기' : '목록과 검색 펼치기'}</button></div> : <p className={styles.description}>{title}를 선택해 정보를 확인하거나 공식 자료로 보완하세요.</p>}
    <div className={`${styles.body} ${id ? styles.withEditor : ''}`}>
      <div className={styles.listPane} hidden={Boolean(id) && !showList}><ManagementList resource={resource} embedded compact={false} refresh={revision} /></div>
      {id ? <section ref={editorRef} tabIndex={-1} className={styles.editor} aria-label={`${title} ${id === 'new' ? '추가' : '상세·수정'}`}>
        <div className={styles.editorHeading}><strong>{id === 'new' ? `${title} 추가` : `${title} 상세·수정`}</strong><Link to={back} aria-label="편집 닫기">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
          닫기
        </Link></div>
        <div className={styles.editorBody}>{id === 'new' ? resource === 'complexes'
          ? <HousingComplexRegistrationPage embedded onCreated={created} />
          : <AnnouncementRegistrationPage embedded onCreated={created} />
          : <ManagementDetail key={`${resource}-${id}`} resource={resource} embedded onChanged={() => setRevision(value => value + 1)} />}</div>
      </section> : null}
    </div>
  </section>
}
