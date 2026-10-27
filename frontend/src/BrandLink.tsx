import { Link } from 'react-router'

type BrandLinkProps = {
  className?: string
  showName?: boolean
  to?: string
  label?: string
}

export function BrandLink({ className, showName = false, to = '/explore', label = '공공주택 복덕방 홈' }: BrandLinkProps) {
  return (
    <Link className={['brand-link', className].filter(Boolean).join(' ')} to={to} aria-label={label}>
      <img className="brand-logo" src="/logo-bok-search.svg" alt="" />
      {showName && <span className="brand-name">공공주택 복덕방</span>}
    </Link>
  )
}
