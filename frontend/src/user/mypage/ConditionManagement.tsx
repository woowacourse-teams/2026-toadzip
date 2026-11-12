import { useId, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { ConditionConfirmDialog } from '../conditions/ConditionConfirmDialog'
import { useConditions } from '../conditions/ConditionsContext'
import { FIELD_COPY, STEP_COPY, type StepNumber } from '../conditions/conditionFieldCopy'
import { displayAnswer } from '../conditions/conditionDisplay'
import { applicableFields, fromProfile, missingFields, stepFields, todayInSeoul, type ConditionDraft } from '../conditions/conditionDraft'
import { formatMonthDay } from '../conditions/conditionFormat'
import type { ProfileField } from '../conditions/conditionsModel'
import styles from './ConditionManagement.module.css'
import { RequestError } from '../../public-housing/components/RequestError'
import { EligibilityDiscoveries } from '../conditions/EligibilityDiscoveries'

export function ConditionManagement({ onBrowseAnnouncements }: { readonly onBrowseAnnouncements: () => void }) {
  const { stored, hasProfile, openInput, clearProfile, processing, processingError, mode, retryProfile } = useConditions()
  const [confirm, setConfirm] = useState(false)
  if (mode === 'error') return <RequestError title="내 조건을 불러오지 못했어요" onRetry={retryProfile ?? (() => {})} />
  if (mode === 'loading') return <p role="status">내 조건을 불러오고 있어요.</p>
  if (!hasProfile || !stored) {
    return <div className={styles.empty}>
      <strong>아직 입력한 내 조건이 없어요</strong>
      <p>입력하면 공고 상세에서 모집 선택지별 신청자격을 확인해 드려요.</p>
      <Button size="lg" onClick={() => openInput({ mode: 'full', step: 1, origin: 'mypage' }, { resume: true })}>내 조건 입력하기</Button>
    </div>
  }
  const draft = fromProfile(stored.profile)
  const missing = missingFields(draft)
  const total = applicableFields(draft).length
  const filled = total - missing.length
  const percent = total ? Math.round((filled / total) * 100) : 0
  const today = todayInSeoul()
  return <div className={styles.body}>
    <div role="status">
      <strong>정보 저장 완료</strong>
      <p>{processingError ? '재판정 진행 상태를 불러오지 못했어요. 저장된 정보는 유지되며 다시 확인하고 있어요.'
        : processing?.state === 'DONE' && processing.profileVersion === stored.version ? '현재 정보의 재판정이 완료됐어요. 공고 변경 여부는 상세에서 다시 확인해요.'
        : '현재 정보로 공고의 신청자격을 다시 확인하고 있어요.'}</p>
    </div>
    <EligibilityDiscoveries onBrowseAnnouncements={onBrowseAnnouncements} />
    <div className={styles.intro}>
      <p>공고 상세의 자격 비교에 쓰는 정보예요. 내 계정에 저장돼요.{stored.savedAt ? ` ${formatMonthDay(stored.savedAt)} 저장` : ''}</p>
      <Button size="sm" variant="secondary" onClick={() => openInput({ mode: 'full', step: 1, origin: 'mypage' }, {})}>처음부터 다시 입력</Button>
    </div>
    <div className={styles.meter}>
      <div className={styles.meterTop}><strong>입력한 항목 {filled} / {total}</strong><span>{percent}%</span></div>
      <div className={styles.track}><span style={{ width: `${percent}%` }} /></div>
      <p>{missing.length
        ? '아래에서 빈 항목의 값을 입력해 주세요.'
        : '모든 항목을 입력했어요. 상황이 바뀌면 바로 고쳐 주세요.'}</p>
      {missing.length > 1 && <Button size="sm" className={styles.fillAll}
        onClick={() => openInput({ mode: 'partial', fields: missing, origin: 'mypage' }, {})}>빈 항목 {missing.length}개 한 번에 입력하기</Button>}
    </div>
    {([1, 2, 3] as const).map((step) => <ConditionGroup key={step} step={step} draft={draft} missing={missing} today={today}
      onEdit={() => openInput({ mode: 'full', step, origin: 'mypage' }, {})}
      onFill={(field) => openInput({ mode: 'partial', fields: [field], origin: 'mypage' }, {})} />)}
    <div className={styles.danger}>
      <button type="button" className={styles.clear} onClick={() => setConfirm(true)}>입력 정보 모두 지우기</button>
      <p>지우면 공고 상세의 자격 비교도 함께 사라져요.</p>
    </div>
    {confirm && <ConditionConfirmDialog danger title="입력 정보를 모두 지울까요?" body="공고 상세의 자격 비교도 함께 사라져요. 다시 비교하려면 처음부터 입력해야 해요."
      cancelLabel="취소" confirmLabel="모두 지우기" onCancel={() => setConfirm(false)} onConfirm={() => { setConfirm(false); clearProfile() }} />}
  </div>
}

function ConditionGroup({ step, draft, missing, today, onEdit, onFill }: {
  readonly step: StepNumber
  readonly draft: ConditionDraft
  readonly missing: readonly ProfileField[]
  readonly today: string
  readonly onEdit: () => void
  readonly onFill: (field: ProfileField) => void
}) {
  const titleId = useId()
  const name = STEP_COPY[step].name
  return <section className={styles.group} aria-labelledby={titleId}>
    <header>
      <h3 id={titleId}><span className={styles.num} aria-hidden="true">{step}</span>{name}</h3>
      <Button size="sm" variant="secondary" aria-label={`${name} 수정`} onClick={onEdit}>수정</Button>
    </header>
    <dl>
      {stepFields(step, draft).map((field) => {
        const absent = missing.includes(field)
        const value = absent ? null : displayAnswer(field, draft, today)
        const prompt = absent ? '값을 입력해 주세요' : '값을 다시 선택해 주세요'
        return <div key={field} className={styles.row}>
          <dt>{FIELD_COPY[field].label}</dt>
          {value === null
            ? <dd><Button className={styles.fillValue} size="md" variant="secondary" aria-label={`${FIELD_COPY[field].label} ${prompt}`} onClick={() => onFill(field)}>{prompt}</Button></dd>
            : <dd>{value}</dd>}
        </div>
      })}
    </dl>
  </section>
}
