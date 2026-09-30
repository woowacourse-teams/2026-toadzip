import { cp, mkdir, readFile } from 'node:fs/promises'

const packageRoot = new URL('../node_modules/pdfjs-dist/', import.meta.url)
const { version } = JSON.parse(await readFile(new URL('package.json', packageRoot), 'utf8'))
const destination = new URL(`../public/pdfjs-${version}/`, import.meta.url)
await mkdir(destination, { recursive: true })
for (const directory of ['cmaps', 'standard_fonts', 'wasm']) {
  await cp(new URL(directory, packageRoot), new URL(directory, destination), { recursive: true })
}
await cp(new URL('LICENSE', packageRoot), new URL('LICENSE', destination))

// Bundle the license beside the locally served HWP renderer assets.
const hwpRoot = new URL('../node_modules/@rhwp/core/', import.meta.url)
const hwp = JSON.parse(await readFile(new URL('package.json', hwpRoot), 'utf8'))
const hwpDestination = new URL(`../public/rhwp-${hwp.version}/`, import.meta.url)
await mkdir(hwpDestination, { recursive: true })
await cp(new URL('LICENSE', hwpRoot), new URL('LICENSE', hwpDestination))
