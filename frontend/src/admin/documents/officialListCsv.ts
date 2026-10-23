import { parseOfficialList, type OfficialComplexList } from './complexCoverage'

// CSV from official portals and tab-separated rows pasted from a spreadsheet.
export function parseOfficialCsv(input: string, metadata: Omit<OfficialComplexList, 'complexes'>): OfficialComplexList {
  if (new TextEncoder().encode(input).length > 1000000) throw new Error('목록 파일은 1MB 이하여야 합니다.')
  const source = input.replace(/^\uFEFF/, '')
  const delimiter = source.split(/\r?\n/, 1)[0].includes('\t') ? '\t' : ','
  const rows: string[][] = []; let row: string[] = []; let cell = ''; let quoted = false; let closed = false
  for (let index = 0; index < source.length; index++) {
    const char = source[index]
    if (quoted) {
      if (char === '"' && source[index + 1] === '"') { cell += '"'; index++ }
      else if (char === '"') { quoted = false; closed = true }
      else cell += char
    } else if (char === '"' && !cell && !closed) quoted = true
    else if (char === delimiter || char === '\n' || char === '\r') {
      row.push(cell.trim()); cell = ''; closed = false
      if (char !== delimiter) { if (row.some(Boolean)) rows.push(row); row = []; if (char === '\r' && source[index + 1] === '\n') index++ }
    } else {
      if (closed || char === '"') throw new Error('CSV 따옴표 형식을 확인해 주세요.')
      cell += char
    }
  }
  if (quoted) throw new Error('CSV 따옴표가 닫히지 않았습니다.')
  row.push(cell.trim()); if (row.some(Boolean)) rows.push(row)
  const header = rows.shift()?.map(value => value.replace(/\s/g, '').toLowerCase()) ?? []
  const name = header.findIndex(value => ['단지명', '아파트명', '주택명', 'name', 'hsmp_nm', 'complex_name', 'complex_label'].includes(value))
  const address = header.findIndex(value => ['도로명주소', '소재지도로명주소', 'roadaddress', 'rn_adres'].includes(value))
  if (name < 0 || new Set(header).size !== header.length) throw new Error('중복 없는 열 제목과 단지명 열이 필요합니다. 도로명주소 열이 없으면 확인 필요로 조사합니다.')
  if (rows.some(values => values.length !== header.length)) throw new Error('CSV 행마다 열 개수가 다릅니다. 구분자와 따옴표를 확인해 주세요.')
  return parseOfficialList(JSON.stringify({ ...metadata, complexes: rows.map(values => ({ name: values[name], roadAddress: address < 0 ? '' : values[address] })) }), 5000)
}
