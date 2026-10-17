import { cp, mkdir, readFile, readdir, writeFile } from 'node:fs/promises'

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

// Keep OCR worker, WASM and language models on this origin; no runtime CDN downloads.
const ocrRoot = new URL('../node_modules/tesseract.js/', import.meta.url)
const ocr = JSON.parse(await readFile(new URL('package.json', ocrRoot), 'utf8'))
const ocrDestination = new URL(`../public/ocr-${ocr.version}/`, import.meta.url)
await mkdir(new URL('core/', ocrDestination), { recursive: true })
await mkdir(new URL('lang/', ocrDestination), { recursive: true })
await cp(new URL('dist/worker.min.js', ocrRoot), new URL('worker.min.js', ocrDestination))
await cp(new URL('LICENSE.md', ocrRoot), new URL('LICENSE.md', ocrDestination))
const coreRoot = new URL('../node_modules/tesseract.js-core/', import.meta.url)
for (const file of await readdir(coreRoot)) {
  if (file.startsWith('tesseract-core') && /\.(?:js|wasm)$/.test(file)) await cp(new URL(file, coreRoot), new URL(`core/${file}`, ocrDestination))
}
await cp(new URL('LICENSE', coreRoot), new URL('core/LICENSE', ocrDestination))
for (const language of ['kor', 'eng']) {
  await cp(new URL(`../node_modules/@tesseract.js-data/${language}/4.0.0_best_int/${language}.traineddata.gz`, import.meta.url),
    new URL(`lang/${language}.traineddata.gz`, ocrDestination))
  const metadata = await readFile(new URL(`../node_modules/@tesseract.js-data/${language}/package.json`, import.meta.url), 'utf8')
  await writeFile(new URL(`lang/${language}.package.json`, ocrDestination), metadata)
}
