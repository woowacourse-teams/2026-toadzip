import { useRef, useState } from 'react'
import { SourceUrl } from '../shared/SourceUrl'
import { EditFields } from './EditFields'
import { display, labels, maxReceptionPlaces, receptionPlaceFields, receptionPlacesOf } from './fields'
import { type ManagementValues } from './managementContract'

function placeTitle(place: ManagementValues, index: number) {
  const method = typeof place.method === 'string' ? labels[place.method] ?? place.method : null
  return `접수처 ${index + 1}${method ? ` · ${method}` : ''}`
}

/** 공고 상세 보기의 접수처. 공고문 순서대로 접수처마다 칸을 보여준다. */
export function ReceptionPlacesView({ data }: { data: ManagementValues }) {
  const places = receptionPlacesOf(data)
  return <section className="admin-detail-section"><h2>접수처</h2>
    {places.length ? places.map((place, index) => <div key={index}><h3>{placeTitle(place, index)}</h3>
      <dl className="admin-data-grid">{receptionPlaceFields.map(field => <div key={field.name} data-field={`receptionPlaces.${field.name}`}><dt>{field.label}</dt>
        <dd>{field.type === 'url' ? <SourceUrl url={place[field.name]} /> : display(place[field.name])}</dd></div>)}</dl></div>)
      : <p>등록된 접수처가 없습니다.</p>}
  </section>
}

/**
 * 공고 기본정보 수정 폼 안의 접수처 목록. 칸 값은 폼이 들고, 여기서는 접수처의 추가·삭제와 순서만 관리한다.
 * 칸 이름은 `receptionPlaces[화면 순서].칸`이라 서버 검증 오류 경로와 같다.
 */
export function ReceptionPlacesEditor({ data, errors, disabled, onChange }: { data: ManagementValues; errors: Record<string, string>; disabled: boolean; onChange: () => void }) {
  const [rows, setRows] = useState(() => receptionPlacesOf(data).map((place, index) => ({ key: index, place })))
  const nextKey = useRef(rows.length)
  function add() {
    const key = nextKey.current++
    setRows(current => [...current, { key, place: { method: 'VISIT' } }]); onChange()
  }
  function remove(key: number) { setRows(current => current.filter(row => row.key !== key)); onChange() }
  return <fieldset disabled={disabled}><legend>접수처</legend>
    <p>공고문에 나온 순서대로 입력합니다. 접수처명·주소·연락처·URL·안내가 모두 빈 접수처는 저장하지 않습니다.</p>
    {errors.receptionPlaces ? <p className="registration-field-error">{errors.receptionPlaces}</p> : null}
    {!rows.length ? <p>등록된 접수처가 없습니다.</p> : null}
    {rows.map((row, index) => <fieldset key={row.key}><legend>접수처 {index + 1}</legend>
      <EditFields fields={receptionPlaceFields} data={row.place} errors={errors} namePrefix={`receptionPlaces[${index}].`} />
      <button type="button" aria-label={`접수처 ${index + 1} 삭제`} onClick={() => remove(row.key)}>삭제</button>
    </fieldset>)}
    <button type="button" className="admin-tonal" disabled={rows.length >= maxReceptionPlaces} onClick={add}>접수처 추가</button>
  </fieldset>
}
