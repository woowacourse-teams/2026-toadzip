import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { getUser, type AdminUser } from './api'
import { joinedAt, providerLabels } from './presentation'
import styles from './Users.module.css'

export function UserDetailPage() {
  const { id = '' } = useParams()
  const [params] = useSearchParams()
  const candidate = params.get('returnTo') ?? ''
  const back = /^\/admin\/users(?:\?[^#]*)?$/.test(candidate) ? candidate : '/admin/users'
  const [loaded, setLoaded] = useState<{ query: string; data: AdminUser } | null>(null)
  const [failure, setFailure] = useState<{ query: string; message: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const valid = /^[1-9]\d*$/.test(id) && Number.isSafeInteger(Number(id))
  const query = `${id}#${attempt}`
  const data = loaded?.query === query ? loaded.data : null
  const error = valid ? failure?.query === query ? failure.message : '' : '올바른 회원 페이지 주소가 아닙니다.'
  useEffect(() => {
    if (!valid) return
    const controller = new AbortController()
    void getUser(id, controller.signal).then(result => {
      if (!controller.signal.aborted) setLoaded({ query, data: result })
    }).catch(cause => {
      if (!controller.signal.aborted) setFailure({ query, message: cause instanceof Error ? cause.message : '회원 정보를 불러오지 못했습니다.' })
    })
    return () => controller.abort()
  }, [id, query, valid])
  return <section className={styles.page}>
    <Link to={back} className={styles.back}>목록으로</Link>
    <header className={styles.heading}><h1>{valid ? `회원 ${id}` : '회원 상세'}</h1></header>
    {error ? <div className={styles.error} role="alert"><p>{error}</p>{valid
      ? <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button> : null}</div> : null}
    {!data && !error ? <p role="status">회원 정보를 불러오는 중…</p> : null}
    {data ? <table className={styles.detail} aria-label="회원 기본 정보"><tbody>
      <tr><th scope="row">회원 ID</th><td>{data.id}</td></tr>
      <tr><th scope="row">로그인 이메일</th><td>{data.email || '미제공'}</td></tr>
      <tr><th scope="row">로그인 방식</th><td>{providerLabels[data.provider]}</td></tr>
      <tr><th scope="row">가입일</th><td><time dateTime={data.createdAt}>{joinedAt(data.createdAt)}</time></td></tr>
    </tbody></table> : null}
  </section>
}
