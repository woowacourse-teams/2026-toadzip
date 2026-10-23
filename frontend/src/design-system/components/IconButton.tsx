import type { ComponentPropsWithRef } from 'react'
import styles from './IconButton.module.css'

export type IconButtonProps = Omit<
  ComponentPropsWithRef<'button'>,
  'aria-label' | 'aria-labelledby'
> & {
  readonly label: string
  readonly size?: 'md' | 'lg'
  readonly variant?: 'plain' | 'filled'
}

export function IconButton({
  label,
  size = 'md',
  variant = 'plain',
  type = 'button',
  className,
  ...props
}: IconButtonProps) {
  return (
    <button
      {...props}
      type={type}
      aria-label={label}
      className={[styles.root, styles[size], styles[variant], className].filter(Boolean).join(' ')}
    />
  )
}
