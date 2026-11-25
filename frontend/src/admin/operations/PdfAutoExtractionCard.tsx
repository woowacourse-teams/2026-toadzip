import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { useConfirm } from '../shared/ConfirmDialog'
import { changePdfAutoExtraction, loadPdfAutoExtraction, type PdfAutoExtraction } from './api'
import { OperationsStatus } from './OperationsStatus'
import { operationTime } from './presentation'
import { useOperationsQuery } from './useOperationsQuery'
import styles from './OperationsHome.module.css'

const publicationModes: Record<PdfAutoExtraction['publicationMode'], string> = {
  OFF: '관리자 검토 뒤 반영', SHADOW: '판정만 기록', APPLY: '맞으면 자동 공개',
}

/** 서버를 다시 띄우지 않고 PDF 자동 추출을 켜고 끈다. 켜면 마감 전 추출 전 공고가 유료로 추출되므로 그 수를 보이고(받을 수 없는 첨부는 건너뛰어 최대치다) 켜기·끄기 모두 확인을 받는다. */
export function PdfAutoExtractionCard() {
  const query = useOperationsQuery(loadPdfAutoExtraction, 0)
  const [changed, setChanged] = useState<PdfAutoExtraction | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [confirm, confirmDialog] = useConfirm()
  const current = changed ?? query.data
  async function toggle(setting: PdfAutoExtraction) {
    const enable = !setting.enabled
    // 끄기는 유료 추출을 멈추는 쪽이고 언제든 다시 켤 수 있어 묻지 않는다.
    if (enable && !(await confirm({ title: '자동 추출을 켤까요?', body: `마감 전 추출 전 공고 최대 ${setting.notExtracted.toLocaleString('ko-KR')}건이 차례로 유료 추출됩니다(동시 ${setting.maxInFlight}건).`, confirmLabel: '자동 추출 켜기' }))) return
    setSaving(true)
    setError('')
    try {
      setChanged(await changePdfAutoExtraction(enable))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '자동 추출 설정을 바꾸지 못했습니다.')
    } finally {
      setSaving(false)
    }
  }
  return <section className={`${styles.panel} ${styles.autoExtraction}`} aria-label="자동 추출">
    <div className={styles.autoExtractionTop}><h2>자동 추출 설정</h2>
      {current && <Button size="sm" variant="secondary" disabled={saving} onClick={() => void toggle(current)}>
        {current.enabled ? '자동 추출 끄기' : '자동 추출 켜기'}</Button>}</div>
    {!changed && <OperationsStatus query={query} />}
    {current && <>
      <dl>
        <div><dt>상태</dt><dd data-enabled={current.enabled}>{current.enabled ? '켜짐' : '꺼짐'}</dd></div>
        <div><dt>자동 공개</dt><dd>{publicationModes[current.publicationMode]}</dd></div>
        <div><dt>추출 전 공고</dt><dd>최대 {current.notExtracted.toLocaleString('ko-KR')}건</dd></div>
        <div><dt>진행 중</dt><dd>{current.inFlight} / 동시 {current.maxInFlight}건</dd></div>
      </dl>
      <p className={styles.message}>{current.updatedBy
        ? <>마지막 변경 {current.updatedBy} · <time dateTime={current.updatedAt ?? undefined}>{operationTime(current.updatedAt)}</time></>
        : '관리자가 바꾼 적 없음 · 서버 기본값'}</p>
    </>}
    {error && <p className={styles.error} role="alert">변경 실패: {error}</p>}
    {confirmDialog}
  </section>
}
