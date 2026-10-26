import type { ReactNode } from 'react'
import styles from './ManagementTable.module.css'

export function ManagementTable({ label, children }: { readonly label: string; readonly children: ReactNode }) {
  return <div className={styles.container}><table className={styles.table} aria-label={label}>
    <thead><tr><th scope="col">관심 대상</th><th scope="col">관리</th></tr></thead>
    <tbody>{children}</tbody>
  </table></div>
}

export function ManagementRow({ children, actions }: { readonly children: ReactNode; readonly actions: ReactNode }) {
  return <tr><td className={styles.target}>{children}</td><td><div className={styles.actions}>{actions}</div></td></tr>
}
