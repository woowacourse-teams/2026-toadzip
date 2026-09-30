import { Link, useNavigate } from 'react-router'
import { HousingComplexRegistrationForm } from './HousingComplexRegistrationForm'
export function HousingComplexRegistrationPage() {
  const navigate = useNavigate()
  return <section className="admin-registration-page">
    <header className="admin-registration-heading"><Link to="/admin/complexes">← 단지 목록</Link><h1>단지 등록</h1>
      <p>주소와 기본 정보를 등록합니다. 저장 후 상세에서 확인하고 공고를 연결할 수 있습니다.</p></header>
    <HousingComplexRegistrationForm disabled={false} onCreated={item => navigate(`/admin/complexes/${item.housingComplexId}`)} />
  </section>
}
