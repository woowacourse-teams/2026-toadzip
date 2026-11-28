import { FeedbackLink } from './FeedbackLink'
import type { FeedbackTarget } from './feedbackNavigation'
import styles from './DetailFeedbackLink.module.css'

/** 공고·단지 상세 맨 아래의 작은 각주. 그 대상을 붙인 채 의견 창을 연다. */
export function DetailFeedbackLink({ target }: { readonly target: FeedbackTarget }) {
  return <p className={styles.report}>
    <span aria-hidden="true">정보가 다른가요?</span>
    <FeedbackLink className={styles.link} feedbackTarget={target} aria-label="정보가 다르면 알려 주세요">알려 주세요</FeedbackLink>
  </p>
}
