import { useEffect, useRef, useState } from 'react'
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
  const editorRef = useRef<HTMLElement>(null)
  const listParams = managementListParams(params, resource)
  const back = managementUrl(resource, listParams)
  const title = resource === 'complexes' ? '단지' : '공고'

  useEffect(() => {
    if (id) editorRef.current?.focus()
  }, [id, resource])

  function created(createdId: number) {
    setRevision(value => value + 1)
    navigate(managementUrl(resource, listParams, String(createdId)), { replace: true })
  }

  return <section className={styles.workspace}>
    <header className={styles.heading}>
      <h1>{title} 관리</h1>
      <Link className="admin-primary" to={managementUrl(resource, listParams, 'new')}>{title} 추가</Link>
    </header>
    <div className={`${styles.body} ${id ? styles.withEditor : ''}`}>
      <ManagementList resource={resource} embedded refresh={revision} />
      {id ? <section ref={editorRef} tabIndex={-1} className={styles.editor} aria-label={`${title} ${id === 'new' ? '추가' : '상세·수정'}`}>
        <div className={styles.editorHeading}><strong>{id === 'new' ? `${title} 추가` : `${title} 상세·수정`}</strong><Link to={back} aria-label="편집 닫기">닫기</Link></div>
        {id === 'new' ? resource === 'complexes'
          ? <HousingComplexRegistrationPage embedded onCreated={created} />
          : <AnnouncementRegistrationPage embedded onCreated={created} />
          : <ManagementDetail key={`${resource}-${id}`} resource={resource} embedded onChanged={() => setRevision(value => value + 1)} />}
      </section> : null}
    </div>
  </section>
}
