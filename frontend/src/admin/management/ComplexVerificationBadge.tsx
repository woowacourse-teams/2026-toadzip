import type { VerificationStatus } from './complexVerificationContract'
import { verificationStatusLabels } from './complexVerificationPresentation'
import styles from './ComplexVerification.module.css'

export function ComplexVerificationBadge({ status, count = 0 }: { status: VerificationStatus; count?: number }) {
  return <span className={styles.badge} data-status={status}>
    {verificationStatusLabels[status]}{count ? <span> · {count}/6항목</span> : null}
  </span>
}
