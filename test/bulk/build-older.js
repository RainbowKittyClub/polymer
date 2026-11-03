'use strict'

// Compile the native and s7 client probes against each pinned older client (older-clients.json).
// 26.x ships deobfuscated, so plain javac against the client jar needs no remapping. Sources under
// older/<version>/ replace the shared file of the same relative path for that version only.

const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')
const repo = path.resolve(__dirname, '../..')
const clients = JSON.parse(fs.readFileSync(path.join(__dirname, 'older-clients.json'), 'utf8'))
const loader = JSON.parse(fs.readFileSync(path.join(__dirname, 'combined-manifest.json'), 'utf8')).loader.libraries
const probes = { native: 'native', s7: 's7' }

function files (root, suffix) {
  if (!fs.existsSync(root)) return []
  return fs.readdirSync(root, { recursive: true }).filter(name => name.endsWith(suffix)).map(name => path.join(root, name))
}

const out = path.join(repo, 'build/older-probes')
fs.rmSync(out, { recursive: true, force: true })
const built = {}
for (const [version, client] of Object.entries(clients)) {
  const classpath = [client.client.path, ...client.libraries.map(entry => entry.path), ...loader.map(entry => entry.path)].join(path.delimiter)
  for (const [name, dir] of Object.entries(probes)) {
    const shared = path.join(__dirname, dir, 'java')
    const override = path.join(__dirname, 'older', version, dir, 'java')
    const sources = new Map(files(shared, '.java').map(file => [path.relative(shared, file), file]))
    for (const file of files(override, '.java')) sources.set(path.relative(override, file), file)
    const classes = path.join(out, version, name, 'classes')
    fs.mkdirSync(classes, { recursive: true })
    execFileSync('javac', ['--release', '25', '-proc:none', '-nowarn', '-encoding', 'UTF-8', '-cp', classpath,
      '-d', classes, ...sources.values()], { stdio: 'inherit' })
    fs.cpSync(path.join(__dirname, dir, 'resources'), classes, { recursive: true })
    const jar = path.join(repo, `build/libs/polymer-bulk-${name}-${version}.jar`)
    fs.rmSync(jar, { force: true })
    // Fixed timestamps and order keep rebuilt jars byte-identical.
    execFileSync('jar', ['--create', '--file', jar, '--date', '2026-01-01T00:00:00Z', '-C', classes, '.'], { stdio: 'inherit' })
    built[`${name}-${version}`] = jar
  }
}
console.log(JSON.stringify(built))
