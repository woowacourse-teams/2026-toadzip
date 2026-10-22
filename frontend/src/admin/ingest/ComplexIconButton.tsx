import { IconButton, type IconButtonProps } from '../../design-system/components/IconButton'
import styles from './ComplexIngest.module.css'

type Icon = 'close' | 'refresh' | 'previous' | 'next'
const paths: Record<Icon, string> = {
  close: 'm6 6 12 12M6 18 18 6',
  refresh: 'M20 7v5h-5M19.1 8.3a8 8 0 1 0 .4 6.7',
  previous: 'm15 6-6 6 6 6',
  next: 'm9 6 6 6-6 6',
}

export function ComplexIconButton({ icon, label, ...props }: Omit<IconButtonProps, 'children' | 'className'> & { icon: Icon }) {
  return <IconButton {...props} data-design-system label={label} title={label} className={styles.iconButton}>
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={paths[icon]} />
    </svg>
  </IconButton>
}
