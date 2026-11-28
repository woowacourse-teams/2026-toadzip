import { feedbackStates, type FeedbackState } from './api'
import styles from './FeedbackListPage.module.css'

export function FeedbackStatusBadge({ status }: { status: FeedbackState }) {
  return <span className={styles.badge} data-status={status}>{feedbackStates[status]}</span>
}
