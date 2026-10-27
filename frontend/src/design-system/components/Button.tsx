import type { ComponentPropsWithRef } from 'react'
import { buttonClassName, type ButtonAppearance } from './buttonClassName'

export type ButtonProps = ComponentPropsWithRef<'button'> & ButtonAppearance

export function Button({ size, variant, type = 'button', className, ...props }: ButtonProps) {
  return (
    <button
      {...props}
      type={type}
      className={buttonClassName({ size, variant }, className)}
    />
  )
}
