import { Link } from 'react-router'

type BrandLinkProps = {
  className?: string
  showName?: boolean
}

export function BrandLink({ className, showName = false }: BrandLinkProps) {
  return (
    <Link className={['brand-link', className].filter(Boolean).join(' ')} to="/" aria-label="공공주택 복덕방 홈">
      <img className="brand-logo" src="/logo-bok-search.svg" alt="" />
      {showName && <span className="brand-name">공공주택 복덕방</span>}
    </Link>
  )
}
