import { Link, useNavigate } from 'react-router'
import { HousingComplexRegistrationForm } from './HousingComplexRegistrationForm'
export function HousingComplexRegistrationPage({ embedded = false, onCreated }: { embedded?: boolean; onCreated?: (id: number) => void }) {
  const navigate = useNavigate()
  return <section className="admin-registration-page">
    {!embedded ? <header className="admin-registration-heading"><Link to="/admin/complexes">← 단지 목록</Link><h1>단지 등록</h1></header> : null}
    <HousingComplexRegistrationForm onCreated={item => onCreated ? onCreated(item.housingComplexId) : navigate(`/admin/complexes/${item.housingComplexId}`)} />
  </section>
}
