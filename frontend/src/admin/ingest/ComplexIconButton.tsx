import { IconButton, type IconButtonProps } from '../../design-system/components/IconButton'
import { AdminIcon, type AdminIconName } from '../shared/AdminIcon'
import styles from './ComplexIngest.module.css'

type Icon = 'close' | 'refresh' | 'previous' | 'next'
const names: Record<Icon, AdminIconName> = { close: 'close', refresh: 'refresh', previous: 'chevronLeft', next: 'chevronRight' }

export function ComplexIconButton({ icon, label, title = label, ...props }: Omit<IconButtonProps, 'children' | 'className'> & { icon: Icon }) {
  return <IconButton {...props} data-design-system label={label} title={title} className={styles.iconButton}>
    <AdminIcon name={names[icon]} size={18} />
  </IconButton>
}
