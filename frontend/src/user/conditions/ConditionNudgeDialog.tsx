import { useId, useRef } from 'react'
import { Button } from '../../design-system/components/Button'
import { IconButton } from '../../design-system/components/IconButton'
import { ConditionModal } from './ConditionModal'
import type { NudgeVariant } from './ConditionsContext'
import { CloseIcon, StatusMark } from './conditionIcons'
import styles from './ConditionNudgeDialog.module.css'

function Title({ variant, draftExists }: { readonly variant: NudgeVariant; readonly draftExists: boolean }) {
  if (draftExists) return <>작성하던 내 조건이 있어요.{' '}<span className={styles.line}>이어서 입력할까요?</span></>
  if (variant === 'list') return <><em>지원 가능한 공고</em>만{' '}<span className={styles.line}>모아 볼 수 있어요</span></>
  if (variant === 'afterLogin') return <>로그인했어요!{' '}<span className={styles.line}>이 공고, 나도 신청할 수 있을까요?</span></>
  return <>이 공고,{' '}<span className={styles.line}>나도 신청할 수 있을까?</span></>
}

/** 입력을 권하는 창. 결과가 어떻게 보이는지 예시로 먼저 보여 준다. */
export function ConditionNudgeDialog({ variant, draftExists, onStart, onLater }: {
  readonly variant: NudgeVariant
  readonly draftExists: boolean
  readonly onStart: () => void
  readonly onLater: () => void
}) {
  const titleId = useId()
  const titleRef = useRef<HTMLHeadingElement>(null)
  return <ConditionModal variant="sheet" labelledBy={titleId} focusRef={titleRef} onCancel={onLater}>
    <div className={styles.content}>
      <div className={styles.close}><IconButton label="닫기" onClick={onLater}><CloseIcon size={24} /></IconButton></div>
      <h2 id={titleId} ref={titleRef} tabIndex={-1}><Title variant={variant} draftExists={draftExists} /></h2>
      <div className={styles.preview} aria-hidden="true">
        <small>결과는 이렇게 보여요</small>
        <ul>
          <li><StatusMark status="ok" size={18} className={styles.mark} />청년<span className={styles.pill} data-status="ok">지원 가능</span></li>
          <li><StatusMark status="tri" size={18} className={styles.mark} />신혼부부<span className={styles.pill} data-status="tri">입력 필요</span></li>
          <li><StatusMark status="no" size={18} className={styles.mark} />고령자<span className={styles.pill} data-status="no">미충족</span></li>
        </ul>
      </div>
      <div className={styles.actions}>
        <Button size="lg" onClick={onStart}>{draftExists ? '이어서 입력하기' : '지금 입력해 보기'}</Button>
        <button type="button" className={styles.later} onClick={onLater}>다음에 할게요</button>
      </div>
      <p className={styles.fine}>마이페이지 &gt; 내 조건 관리에서도 입력할 수 있어요</p>
    </div>
  </ConditionModal>
}
