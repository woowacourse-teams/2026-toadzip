import type { MarkStatus } from './conditionMarks'

type IconProps = { readonly size?: number; readonly className?: string }

function Svg({ size = 16, className, strokeWidth = 2, children }: IconProps & { readonly strokeWidth?: number; readonly children: React.ReactNode }) {
  return <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>
}

export const TickIcon = (props: IconProps) => <Svg {...props} strokeWidth={3}><path d="m5 12 4.5 4.5L19 7" /></Svg>
export const ChevronDownIcon = (props: IconProps) => <Svg {...props} strokeWidth={1.8}><path d="m6 9 6 6 6-6" /></Svg>
export const ChevronRightIcon = (props: IconProps) => <Svg {...props}><path d="m9 6 6 6-6 6" /></Svg>
export const BackIcon = (props: IconProps) => <Svg {...props}><path d="m15 5-7 7 7 7" /></Svg>
export const CloseIcon = (props: IconProps) => <Svg {...props}><path d="m5 5 14 14M19 5 5 19" /></Svg>
export const PencilIcon = (props: IconProps) => <Svg {...props}><path d="M4 20h4L19 9l-4-4L4 16z" /><path d="m13.5 6.5 4 4" /></Svg>
export const PlusIcon = (props: IconProps) => <Svg {...props} strokeWidth={2.2}><path d="M12 5v14M5 12h14" /></Svg>
export const DocIcon = (props: IconProps) => <Svg {...props}><path d="M6 2h8l5 5v15H6z" /><path d="M14 2v5h5" /></Svg>
export const InfoIcon = (props: IconProps) => <Svg {...props}><circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 8h.01" /></Svg>
export const WarnIcon = (props: IconProps) => <Svg {...props}><path d="M12 3 22 20H2z" /><path d="M12 10v4M12 17h.01" /></Svg>
export const LockIcon = (props: IconProps) => <Svg {...props}><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></Svg>
export const BigCheckIcon = (props: IconProps) => <Svg {...props} strokeWidth={2.6}><path d="m5 12 5 5 9-10" /></Svg>

/** ○ × △ − 기호. label이 있으면 그림으로 읽힌다. */
export function StatusMark({ status, size = 20, label, className, bold = false }: { readonly status: MarkStatus; readonly size?: number; readonly label?: string; readonly className?: string; readonly bold?: boolean }) {
  const a11y = label ? { role: 'img', 'aria-label': label } : { 'aria-hidden': true as const }
  const width = bold ? 3.4 : 2.6
  return <svg className={className} data-mark={status} width={size} height={size} viewBox="0 0 24 24" fill="none" {...a11y}>
    {status === 'ok' && <circle cx="12" cy="12" r={bold ? 6.8 : 8} stroke="currentColor" strokeWidth={width} />}
    {status === 'no' && <path d={bold ? 'M7 7l10 10M17 7 7 17' : 'M6.5 6.5l11 11M17.5 6.5l-11 11'} stroke="currentColor" strokeWidth={width} strokeLinecap="round" />}
    {status === 'tri' && <path d={bold ? 'M12 5.2 19.6 18.6H4.4z' : 'M12 4.5 20.5 19.5h-17z'} stroke="currentColor" strokeWidth={bold ? 3 : 2.4} strokeLinejoin="round" />}
    {status === 'na' && <path d="M6 12h12" stroke="currentColor" strokeWidth={2.6} strokeLinecap="round" />}
  </svg>
}
