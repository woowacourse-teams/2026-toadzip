import styles from './Button.module.css'

export type ButtonAppearance = {
  readonly size?: 'md' | 'lg'
  readonly variant?: 'primary' | 'secondary'
}

// 이동은 실제 링크로 두고 같은 모양만 입힐 때 사용한다.
export function buttonClassName({ size = 'md', variant = 'primary' }: ButtonAppearance = {}, className?: string) {
  return [styles.root, styles[size], styles[variant], className].filter(Boolean).join(' ')
}
