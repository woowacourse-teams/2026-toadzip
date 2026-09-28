import type { ComponentPropsWithRef } from 'react'
import styles from './Button.module.css'

export type ButtonProps = ComponentPropsWithRef<'button'> & {
  readonly size?: 'md' | 'lg'
}

export function Button({ size = 'md', type = 'button', className, ...props }: ButtonProps) {
  return (
    <button
      {...props}
      type={type}
      className={[styles.root, styles[size], className].filter(Boolean).join(' ')}
    />
  )
}
