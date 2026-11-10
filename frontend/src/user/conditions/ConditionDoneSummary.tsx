import { useId } from 'react'
import { Button } from '../../design-system/components/Button'
import { FIELD_COPY, STEP_COPY, type StepNumber } from './conditionFieldCopy'
import { displayAnswer } from './conditionDisplay'
import { applicableFields, missingFields, stepFields, type ConditionDraft } from './conditionDraft'
import { BigCheckIcon, LockIcon } from './conditionIcons'
import type { ProfileField } from './conditionsModel'
import styles from './ConditionDoneSummary.module.css'

/** 입력 완료 화면: 단계별 작은 표. 나중에 채울 항목은 같은 자리에 빨간 "미입력". */
export function ConditionDoneSummary({ draft, today, onEdit }: { readonly draft: ConditionDraft; readonly today: string; readonly onEdit: (step: StepNumber) => void }) {
  const missing = new Set(missingFields(draft))
  const total = applicableFields(draft).length
  const filled = total - missing.size
  return <>
    <div className={styles.hero}>
      <span className={styles.big}><BigCheckIcon size={30} /></span>
      <h3>입력을 마쳤어요</h3>
      <p>이제 이 공고와 비교할 준비가 됐어요.</p>
    </div>
    <div className={styles.meter}>
      <p><span>입력한 항목 <b>{filled}</b> / {total}</span>{missing.size > 0 && <span className={styles.later}>미입력 {missing.size}개는 나중에 채울 수 있어요</span>}</p>
      <div className={styles.track}><span style={{ width: `${total ? Math.round((filled / total) * 100) : 0}%` }} /></div>
    </div>
    {([1, 2, 3] as const).map((step) => <SummaryGroup key={step} step={step} draft={draft} today={today} missing={missing} onEdit={onEdit} />)}
    <p className={styles.privacy}><LockIcon size={15} /><span>저장하면 입력한 정보는 내 계정에 보관돼요. 마이페이지의 내 조건 관리에서 언제든 고치거나 지울 수 있어요.</span></p>
  </>
}

function SummaryGroup({ step, draft, today, missing, onEdit }: { readonly step: StepNumber; readonly draft: ConditionDraft; readonly today: string; readonly missing: ReadonlySet<ProfileField>; readonly onEdit: (step: StepNumber) => void }) {
  const titleId = useId()
  return <section className={styles.group} aria-labelledby={titleId}>
    <header><strong id={titleId}>{step}. {STEP_COPY[step].name}</strong><Button size="sm" variant="secondary" onClick={() => onEdit(step)}>수정</Button></header>
    <dl>
      {stepFields(step, draft).map((field) => {
        const absent = missing.has(field)
        return <div key={field} className={styles.row}><dt>{FIELD_COPY[field].label}</dt>
          <dd className={absent ? styles.missing : undefined}>{absent ? '미입력' : displayAnswer(field, draft, today)}</dd></div>
      })}
    </dl>
  </section>
}
