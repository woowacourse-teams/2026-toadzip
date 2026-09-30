import styles from './AnnouncementStatusBadge.module.css'

interface AnnouncementStatusBadgeProps {
  readonly label: string
  readonly tone: 'applying' | 'upcoming' | 'closed' | 'cancelled' | 'unknown'
  readonly countdown: {
    readonly visible: string
    readonly accessible: string
  } | null
}

export function AnnouncementStatusBadge({
  label,
  tone,
  countdown,
}: AnnouncementStatusBadgeProps) {
  return (
    <span className={styles.badge} data-status-badge data-tone={tone}>
      <span>{label}</span>
      {countdown && (
        <>
          <i className={styles.separator} aria-hidden="true">|</i>
          <span
            className={styles.countdown}
            data-status-kind="countdown"
            aria-label={countdown.accessible}
          >
            {countdown.visible}
          </span>
        </>
      )}
    </span>
  )
}
