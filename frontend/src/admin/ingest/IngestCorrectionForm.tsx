import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ManagementDetail } from '../management/ManagementDetail'
import { ManagementError } from '../management/api'
import { Button } from '../../design-system/components/Button'
import { getCorrection, saveCorrection, type CorrectionDetail, type IngestDomain, type Scalar } from './workspaceApi'
import styles from './IngestWorkspace.module.css'

const labels: Record<string, string> = {
  hsmpNm: '단지명', insttNm: '공급기관', rnAdres: '도로명주소', pnu: 'PNU', brtcCode: '시·도 코드', signguCode: '시·군·구 코드',
  hshldCo: '전체 세대수', parkngCo: '주차대수', styleNm: '주택형명', suplyPrvuseAr: '전용면적', suplyCmnuseAr: '공용면적',
  competDe: '준공일', heatMthdDetailNm: '난방방식', houseTyNm: '주택유형', buldStleNm: '복도유형', elvtrInstlAtNm: '승강기 설치 여부',
  pblancNm: '공고명', suplyInsttNm: '공급기관', suplyTyNm: '공급유형', sttusNm: '공고 구분', beforePblancId: '이전 공고 ID',
  rcritPblancDe: '공고일', przwnerPresnatnDe: '당첨자 발표일', beginDe: '접수 시작일', endDe: '접수 종료일',
  url: '원문 URL', pcUrl: 'PC 원문 URL', mobileUrl: '모바일 원문 URL', sumSuplyCo: '공급 세대수', fullAdres: '주소',
}
const numericFields = new Set(['hshldCo', 'parkngCo', 'suplyPrvuseAr', 'suplyCmnuseAr', 'sumSuplyCo'])
const requiredFields = {
  complex: new Set(['hsmpNm', 'insttNm', 'rnAdres', 'pnu', 'brtcCode', 'signguCode', 'hshldCo', 'parkngCo', 'styleNm', 'suplyPrvuseAr']),
  announcement: new Set(['pblancNm', 'suplyInsttNm', 'suplyTyNm', 'houseTyNm', 'sttusNm', 'rcritPblancDe', 'przwnerPresnatnDe', 'beginDe', 'endDe', 'hsmpNm', 'pnu']),
}

export function IngestCorrectionForm({ domain, identifier, disabled, onSaved, onClose, onBusyChange }: {
  domain: IngestDomain; identifier: string; disabled: boolean; onSaved: (id: number) => void; onClose: () => void
  onBusyChange?: (busy: boolean) => void
}) {
  const [detail, setDetail] = useState<CorrectionDetail | null>(null)
  const [inputs, setInputs] = useState<Record<string, Record<string, string>>>({})
  const [latitude, setLatitude] = useState('')
  const [longitude, setLongitude] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => { onBusyChange?.(busy) }, [busy, onBusyChange])
  useEffect(() => () => { onBusyChange?.(false) }, [onBusyChange])
  useEffect(() => {
    const controller = new AbortController(); setError(''); setDetail(null)
    void getCorrection(domain, identifier, controller.signal).then(value => {
      if (controller.signal.aborted) return
      setDetail(value)
      setInputs(Object.fromEntries(value.rows.map(row => [row.sourceKey,
        Object.fromEntries(value.editableFields.map(key => [key, String(row.values[key] ?? '')]))])))
      setLatitude(String(value.latitude ?? '')); setLongitude(String(value.longitude ?? ''))
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '보완 정보 조회 실패') })
    return () => controller.abort()
  }, [domain, identifier, attempt])

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!detail || busy || disabled) return
    setBusy(true); setError('')
    try {
      const grouped = new Map<string, Record<string, Scalar>>()
      for (const row of detail.rows) {
        const changes: Record<string, Scalar> = grouped.get(row.sourceKey) ?? {}
        for (const key of detail.editableFields) {
          const text = inputs[row.sourceKey]?.[key]?.trim() ?? ''
          const value = text === '' ? null : numericFields.has(key) ? Number(text) : text
          if (typeof value === 'number' && !Number.isFinite(value)) throw new Error(`${labels[key]}에 숫자를 입력해 주세요.`)
          if (String(value ?? '') !== String(row.original[key] ?? '')) changes[key] = value
        }
        grouped.set(row.sourceKey, changes)
      }
      const rows = [...grouped].map(([sourceKey, changes]) => ({ sourceKey, changes }))
      const lat = coordinate(latitude); const lon = coordinate(longitude)
      const productId = await saveCorrection(detail, rows, lat, lon)
      onSaved(productId)
      setDetail(await getCorrection(domain, identifier))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '보완 저장 실패')
      // 보완값은 서버에 보존되었을 수 있다. 입력은 유지하고 다음 저장에 사용할 버전만 갱신한다.
      if (!(cause instanceof ManagementError && cause.status === 409)) {
        try { setDetail(await getCorrection(domain, identifier)) } catch { /* 입력값과 기존 오류를 유지한다. */ }
      }
    } finally { setBusy(false) }
  }
  const resource = domain === 'complex' ? 'complexes' : 'announcements'
  return <div>
    <div className={styles.actions}><h3>데이터 확인·보완 · {identifier}</h3>
      <button type="button" disabled={busy} onClick={onClose}>보완 닫기</button>
      <button type="button" disabled={busy} onClick={() => setAttempt(value => value + 1)}>정보 다시 조회</button></div>
    {error && <p role="alert">{error}</p>}
    {!detail && !error && <p role="status">보완 정보를 불러오는 중…</p>}
    {domain === 'announcement' && <p>필요한 단지가 없으면 <Link to="/admin/ingest-v2?domain=complex">단지 탭</Link>에서 단지·주택형을 먼저 등록해 주세요.</p>}
    {detail?.managementOnly && detail.productId && <>
      <p>원천이 없거나 기존 관리자 수정값이 있는 데이터입니다. 기존 수정 화면에서 보완합니다.</p>
      <ManagementDetail resource={resource} identifier={String(detail.productId)} />
    </>}
    {detail?.managementOnly && !detail.productId && <p>사용 가능한 원천이 없습니다. 먼저 수집을 실행해 주세요.</p>}
    {detail && !detail.managementOnly && <form onSubmit={event => { void save(event) }} className={styles.form} aria-label="데이터 보완 저장">
      <p>원본 API 응답은 보존됩니다. 날짜는 원천 형식(예: 20261008), 기관·유형은 원천의 명칭으로 입력합니다.</p>
      {detail.rows.map((row, index) => <fieldset key={`${row.sourceKey}:${index}`} disabled={busy || disabled}>
        <legend>원천 행 {index + 1}</legend>
        {detail.editableFields.map(key => <label key={key}>
          {labels[key] ?? key}
          <input aria-label={`${labels[key] ?? key} · 행 ${index + 1}`} value={inputs[row.sourceKey]?.[key] ?? ''}
            type={numericFields.has(key) ? 'number' : 'text'} step={numericFields.has(key) ? 'any' : undefined}
            onChange={event => setInputs(previous => ({ ...previous,
              [row.sourceKey]: { ...previous[row.sourceKey], [key]: event.target.value } }))} />
          {requiredFields[domain].has(key) && !(inputs[row.sourceKey]?.[key] ?? '').trim()
            && <span className={styles.missing}>필수 값 누락</span>}
          <span className={styles.original}>원천: {String(row.original[key] ?? '미제공')}</span>
        </label>)}
      </fieldset>)}
      {domain === 'complex' && <fieldset disabled={busy || disabled}><legend>좌표 보완 (선택)</legend>
        <label>위도<input type="number" step="any" value={latitude} onChange={event => setLatitude(event.target.value)} /></label>
        <label>경도<input type="number" step="any" value={longitude} onChange={event => setLongitude(event.target.value)} /></label>
        <p>좌표를 입력하지 않으면 기존 도로명주소 좌표 데이터를 사용합니다.</p>
      </fieldset>}
      <Button type="submit" disabled={busy || disabled}>{busy ? '정제·저장 중…' : '보완 후 정제·저장'}</Button>
    </form>}
    {detail && detail.changes.length > 0 && <details><summary>최근 보완 이력</summary>
      {detail.changes.map((change, index) => <details key={`${change.occurredAt}:${index}`}><summary>{change.actor} · {new Date(change.occurredAt).toLocaleString('ko-KR')}</summary>
        <pre>{change.beforeValue}</pre><pre>{change.afterValue}</pre></details>)}
    </details>}
  </div>
}

function coordinate(text: string): number | null {
  if (!text.trim()) return null
  const value = Number(text)
  if (!Number.isFinite(value)) throw new Error('좌표에 숫자를 입력해 주세요.')
  return value
}
