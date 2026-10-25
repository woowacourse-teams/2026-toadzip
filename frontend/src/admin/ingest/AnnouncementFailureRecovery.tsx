import { useEffect, useState } from 'react'
import { AnnouncementSupplyMatchingForm } from './AnnouncementSupplyMatchingForm'
import { getFailureReviews } from './failureReviewApi'
import styles from './IngestFailurePanel.module.css'

export const announcementRecoveryId = 'announcement-failure-recovery'

/**
 * 공고의 단지·주택형 매칭 오류를 상세 화면 안에서 복구한다. 실패한 공급행을 바로 고치고 이 공고만 다시 정제한 뒤,
 * 같은 공고에 남은 미해결 오류 수를 정제 전과 비교해 보여 준다. 한 원인이 여러 공급행 오류로 남기 때문에 건수로 판단한다.
 */
export function AnnouncementFailureRecovery({ identifier, disabled, onApplied, onBusy }: {
  identifier: string; disabled: boolean; onApplied: () => void; onBusy: (busy: boolean) => void
}) {
  const [before, setBefore] = useState<number | null>(null)
  const [after, setAfter] = useState<number | null>(null)
  const [countError, setCountError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    void pendingCount(identifier, controller.signal).then(setBefore).catch(() => undefined)
    return () => controller.abort()
  }, [identifier])

  async function applied() {
    setCountError('')
    try { setAfter(await pendingCount(identifier)) }
    catch { setCountError('정제는 마쳤지만 남은 오류 수를 확인하지 못했습니다. 목록에서 확인해 주세요.') }
    onApplied()
  }

  return <section id={announcementRecoveryId} className={styles.recovery} aria-labelledby="announcement-recovery-title" tabIndex={-1}>
    <h4 id="announcement-recovery-title">여기서 바로 복구</h4>
    <p className={styles.hint}>{before !== null ? `이 공고에 미해결 오류 ${before}건이 있습니다. ` : ''}
      아래 공급행의 단지·주택형을 정하고 정제를 실행하면 이 공고만 다시 정제하고, LH 보강도 함께 다시 판정합니다.</p>
    <AnnouncementSupplyMatchingForm identifier={identifier} focusFailed disabled={disabled}
      onApplied={() => { void applied() }} onBusy={onBusy} />
    {after !== null && <p role="status" className={after === 0 ? styles.recoveryDone : styles.recoveryLeft}>
      {after === 0
        ? `이 공고의 미해결 오류가 모두 해결됐습니다${before ? ` (${before}건 → 0건)` : ''}.`
        : `이 공고에 미해결 오류 ${after}건이 남았습니다${before !== null ? ` (${before}건 → ${after}건)` : ''}. 목록에서 남은 오류를 열어 이어서 처리하세요.`}
    </p>}
    {countError && <p role="alert">{countError}</p>}
  </section>
}

// 오류 검색은 부분 일치라 다른 공고 번호도 섞일 수 있어 원천 공고 번호가 같은 오류만 센다.
async function pendingCount(identifier: string, signal?: AbortSignal): Promise<number> {
  let total = 0
  for (let page = 0; ; page++) {
    const result = await getFailureReviews('announcement', 'all', 'PENDING', page, signal, { keyword: identifier })
    total += result.items.filter(item => item.raw.sourceAnnouncementIdentifier === identifier).length
    if (!result.hasNext) return total
  }
}
