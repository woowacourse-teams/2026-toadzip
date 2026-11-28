import { FeedbackLink } from './FeedbackLink'
import type { FeedbackTarget } from './feedbackNavigation'
import styles from './DetailFeedbackLink.module.css'

/** 공고·단지 상세 맨 아래에서 그 대상을 붙인 채 의견 창을 연다. */
export function DetailFeedbackLink({ target }: { readonly target: FeedbackTarget }) {
  return <p className={styles.report}>
    <FeedbackLink className={styles.link} feedbackTarget={target}>정보가 다르면 알려 주세요</FeedbackLink>
  </p>
}
