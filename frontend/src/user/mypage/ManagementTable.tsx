import type { ReactNode } from 'react'
import styles from './ManagementTable.module.css'

export function ManagementTable({ label, detailLabel, children }: {
  readonly label: string
  readonly detailLabel?: string
  readonly children: ReactNode
}) {
  return <div className={styles.container}><table className={styles.table} aria-label={label} role="table" data-has-details={detailLabel ? true : undefined}>
    <thead role="rowgroup"><tr role="row"><th scope="col" role="columnheader">관심 대상</th>{detailLabel && <th scope="col" role="columnheader" className={styles.detailsHeading}>{detailLabel}</th>}<th scope="col" role="columnheader">관리</th></tr></thead>
    <tbody role="rowgroup">{children}</tbody>
  </table></div>
}

export function ManagementRow({ children, details, actions }: {
  readonly children: ReactNode
  readonly details?: ReactNode
  readonly actions: ReactNode
}) {
  return <tr role="row"><td role="cell" className={styles.target}>{children}</td>{details !== undefined && <td role="cell" className={styles.details}>{details}</td>}<td role="cell" className={styles.actionsCell}><div className={styles.actions}>{actions}</div></td></tr>
}
