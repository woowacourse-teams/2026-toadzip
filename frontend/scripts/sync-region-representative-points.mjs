import { readFile, writeFile } from 'node:fs/promises'

const source = new URL('../../backend/src/main/resources/map-clustering/representative-points.csv', import.meta.url)
const destination = new URL('../src/public-housing/regions/regionRepresentativePoints.json', import.meta.url)
const rows = (await readFile(source, 'utf8')).trim().split('\n').slice(1)
const points = {}
for (const row of rows) {
  const [, , groupKey, latitude, longitude] = row.split(',')
  if (groupKey?.startsWith('BASIC_REGION:')) {
    points[groupKey.slice('BASIC_REGION:'.length)] = { latitude: Number(latitude), longitude: Number(longitude) }
  }
}
await writeFile(destination, `${JSON.stringify(points, null, 2)}\n`)
