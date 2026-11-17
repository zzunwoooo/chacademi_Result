const fs = require('fs')
const path = require('path')
const asar = require('@electron/asar')

exports.default = async context => {
    const root = path.resolve(__dirname, '..')
    const metadata = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'))
    const archive = path.join(context.appOutDir, 'resources', 'app.asar')
    const entries = new Set(asar.listPackage(archive).map(value => value.replaceAll('\\', '/').replace(/^\//, '')))
    const missing = Object.keys(metadata.dependencies).filter(name => !entries.has(`node_modules/${name}/package.json`))
    if (!entries.has('node_modules/@electron/remote/main/index.js')) missing.push('@electron/remote/main')
    if (missing.length) throw new Error(`Packaged runtime dependencies missing: ${missing.join(', ')}`)
    console.log(`Verified ${Object.keys(metadata.dependencies).length} packaged runtime dependencies and @electron/remote/main`)
}
