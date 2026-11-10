import { STEP_COPY, type StepNumber } from './conditionFieldCopy'
import { stepComplete, type ConditionDraft } from './conditionDraft'
import { TickIcon } from './conditionIcons'
import styles from './ConditionStepProgress.module.css'

/** 지난 단계는 진한 초록, 가 봤던 뒤 단계는 연한 초록. 둘 다 눌러서 이동한다. */
export function ConditionStepProgress({ step, maxStep, draft, onGo }: {
  readonly step: StepNumber | 4
  readonly maxStep: number
  readonly draft: ConditionDraft
  readonly onGo: (step: StepNumber) => void
}) {
  const reached = Math.max(maxStep, step)
  return <div className={styles.progress}>
    <p className={styles.count}><b>{step > 3 ? '입력 완료' : `${step} / 3 단계`}</b></p>
    <ol className={styles.bars} aria-label="입력 단계">
      {([1, 2, 3] as const).map((n) => {
        const state = n === step ? 'current' : n < step ? 'done' : n <= reached ? 'ahead' : 'todo'
        const name = STEP_COPY[n].name
        if (state === 'done' || state === 'ahead') {
          return <li key={n} data-state={state}>
            <button type="button" className={styles.segment} aria-label={`${name} 단계로 ${state === 'done' ? '돌아가기' : '이동'}`} onClick={() => onGo(n)}>
              <span className={styles.bar} />
              <span className={styles.label}>{name}{stepComplete(n, draft) && <TickIcon size={11} />}</span>
            </button>
          </li>
        }
        return <li key={n} data-state={state} aria-current={state === 'current' ? 'step' : undefined}>
          <span className={styles.bar} /><span>{name}</span>
        </li>
      })}
    </ol>
  </div>
}
