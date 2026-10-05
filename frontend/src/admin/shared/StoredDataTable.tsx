import { announcementSections, complexSections, supplyFields } from '../management/fields'
import { storedDataRows, storedDataValue } from './storedDataPresentation'
import styles from './StoredDataTable.module.css'

const fieldLabels = new Map<string, string>([
  ...complexSections.flatMap(section => section.fields),
  ...announcementSections.flatMap(section => section.fields),
  ...supplyFields,
].map(field => [field.name, field.label]))

export function StoredDataTable({ data, label }: { data: unknown; label: string }) {
  const rows = storedDataRows(data)
  return (
    <div className={styles.scroll} tabIndex={0} role="region" aria-label={`${label} 가로 스크롤`}>
      <table className={styles.table}>
        <caption>{label}</caption>
        <thead><tr><th scope="col">항목</th><th scope="col">값</th></tr></thead>
        <tbody>{rows.map(({ path, value }, index) => {
          const normalizedPath = path.replace(/\[\d+\]/g, '')
          const fieldLabel = fieldLabels.get(normalizedPath) ?? fieldLabels.get(normalizedPath.split('.').at(-1) ?? '')
          return <tr key={`${path}-${index}`}>
            <th scope="row">{fieldLabel ? <span className={styles.fieldLabel}>{fieldLabel}</span> : null}<span>{path}</span></th>
            <td>{storedDataValue(path, value)}</td>
          </tr>
        })}</tbody>
      </table>
    </div>
  )
}
