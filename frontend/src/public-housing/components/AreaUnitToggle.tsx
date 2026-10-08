import type { AreaUnit } from '../presentation/housingArea'
import styles from './AreaUnitToggle.module.css'

export function AreaUnitToggle({ unit, onToggle }: { unit: AreaUnit; onToggle: () => void }) {
  return (
    <button type="button" className={styles.button} onClick={onToggle}>
      <svg aria-hidden="true" focusable="false" viewBox="0 0 20 20" fill="none"
        stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M3 6h14m-4-4 4 4-4 4M17 14H3m4-4-4 4 4 4" />
      </svg>
      <span>{unit === 'sqm' ? '평 전환' : '㎡ 전환'}</span>
    </button>
  )
}
