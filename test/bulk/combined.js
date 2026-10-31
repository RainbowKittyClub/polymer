'use strict'

// Combined FAWE + Polymer gate: FAWE's coordinated direct-section edits must reconcile Polymer
// holders, per-recipient block mapping and light exactly like ordinary writes, with both halves
// installed as jars. FAWE side drives /fawe-coordinated commands (requires
// -Dfawe.experimental.coordinated=true); Polymer side is the accepted 0.18.6-fawe-s7.1 bundle.

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const prior = '/home/kgadberry/projects/minecraft-dev/rkc/forks/FabricAsyncWorldEdit/.claude/worktrees/wf_6f7c091d-06c-3/test/harness/'
const { Rig, SingleLaneLease, admitResources, writeJson, json, hash, verify, tool } = require(prior + 'rig')
const { DisplayLease } = require(prior + 'display')
const { Rcon } = require(prior + 'rcon')
const { classpath, offlineUuid } = require(prior + 'native')
const repo = path.resolve(__dirname, '../..')

// Lamp placed LAST: a subsequently placed adjacent block would neighbor-update the unpowered
// lit lamp and toggle it off. Order matters: oak and birch first, then the lamp at the probe cell.
const LAMP = 'minecraft:redstone_lamp[lit=true]'
const CELLS = [[3200, 80, 3200, 'oak'], [3202, 80, 3200, 'birch'], [3201, 80, 3200, 'lamp']]

async function client (rig, port, name) {
  if (!process.env.TMPDIR) throw new Error('TMPDIR required')
  const temp = fs.mkdtempSync(path.join(process.env.TMPDIR, 'polymer-native-'))
  fs.chmodSync(temp, 0o700); rig.tempDirs.push(temp)
  const auth = path.join(temp, 'Xauthority')
  fs.writeFileSync(auth, '', { mode: 0o600 })
  const lease = new DisplayLease(); rig.displays.push(lease)
  tool('xauth', ['-f', auth, 'add', `:${lease.number}`, '.', crypto.randomBytes(16).toString('hex')])
  writeJson(path.join(rig.root, `display-${name}.json`), { number: lease.number, before: lease.before })
  lease.beforeSpawn()
  const display = rig.spawn(`display-${name}`, 'Xvfb', [`:${lease.number}`, '-screen', '0', '854x480x24', '-nolisten', 'tcp', '-auth', auth, '-noreset'])
  lease.attach(display)
  await rig.wait('private display', () => lease.ready(), 15000, [display])
  const dir = path.join(rig.root, name)
  fs.mkdirSync(path.join(dir, 'mods'), { recursive: true })
  fs.copyFileSync(verify(rig.manifest.artifacts.native), path.join(dir, 'mods/probe.jar'))
  fs.writeFileSync(path.join(dir, 'options.txt'), 'version:5023\nonboardAccessibility:false\nstartedCleanly:true\npauseOnLostFocus:false\nenableVsync:false\nmaxFps:30\nrenderDistance:3\nsimulationDistance:5\nnarrator:0\n')
  const env = { ...process.env, DISPLAY: `:${lease.number}`, XAUTHORITY: auth, XDG_RUNTIME_DIR: temp,
    SDL_VIDEO_DRIVER: 'x11', LIBGL_ALWAYS_SOFTWARE: '1', ALSOFT_DRIVERS: 'null', VK_LOADER_LAYERS_DISABLE: '~implicit~' }
  for (const key of ['WAYLAND_DISPLAY', 'DBUS_SESSION_BUS_ADDRESS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS']) delete env[key]
  const request = path.join(dir, 'request.json'), events = path.join(dir, 'events.jsonl')
  const entry = rig.spawn(name, 'java', ['-Xmx3G', `-Dpolymer.bulk.pack=${name === 'polymertwo'}`, `-Dclaude.scratch=fawe-${rig.token}`,
    '--enable-native-access=ALL-UNNAMED', '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED', '-XX:StackShadowPages=32',
    `-Dpolymer.bulk.request=${request}`, `-Dpolymer.bulk.events=${events}`, `-Dpolymer.bulk.packets=${path.join(dir, 'packets.jsonl')}`, '-cp', classpath(rig.manifest, 'client'),
    'net.fabricmc.loader.impl.launch.knot.KnotClient', '--username', name, '--uuid', offlineUuid(name), '--accessToken', '0',
    '--offlineDeveloperMode', '--graphicsBackend', 'OPENGL', '--version', '26.3', '--versionType', 'release', '--gameDir', dir,
    '--assetsDir', rig.manifest.minecraft.assetsRoot, '--assetIndex', rig.manifest.minecraft.assetIndexName,
    '--width', '854', '--height', '480', '--quickPlayMultiplayer', `127.0.0.1:${port}`], { cwd: dir, env })
  const read = () => fs.existsSync(events) ? fs.readFileSync(events, 'utf8').split('\n').filter(Boolean).map(JSON.parse) : []
  await rig.wait('native world', () => read().some(event => event.state === 'world'), 180000, [entry, display])
  const send = async action => {
    const id = crypto.randomUUID()
    writeJson(request + '.new', { id, ...action }); fs.renameSync(request + '.new', request)
    return rig.wait('native observation', () => read().find(event => event.id === id), 30000, [entry, display])
  }
  return { entry, send }
}

async function main () {
  let lease, rig, rcon, server, failure
  const records = []
  const report = { accepted: false, scope: 'combined FAWE coordinated direct-section + Polymer reconciliation, not full S7 or S12', negativeControls: [] }
  const manifestFile = path.join(__dirname, 'combined-manifest.json')
  const manifest = json(manifestFile)
  const sourcePaths = fs.readFileSync(path.join(repo, 'handoff/s7-polymer/changed-files.txt'), 'utf8').trim().split('\n')
  for (const entry of fs.readdirSync(__dirname, { recursive: true, withFileTypes: true })) {
    if (entry.isFile()) sourcePaths.push(path.relative(repo, path.join(entry.parentPath, entry.name)))
  }
  report.sourceHashes = Object.fromEntries([...new Set(sourcePaths)].sort().map(name => [name, hash(path.join(repo, name))]))
  const reportFile = path.join(repo, 'handoff/s7-polymer', `combined-${Date.now()}.json`)
  try {
    lease = new SingleLaneLease()
    const admission = await admitResources()
    rig = new Rig('/mnt/WORKSPACE/scratchpad/fawe', manifest, manifestFile, admission)
    report.root = rig.root
    const dir = path.join(rig.root, 'server')
    fs.mkdirSync(path.join(dir, 'mods'), { recursive: true })
    fs.mkdirSync(path.join(dir, 'config/polymer'), { recursive: true })
    report.artifacts = manifest.artifacts
    for (const [name, key] of manifest.mods) {
      fs.copyFileSync(verify(manifest.artifacts[key]), path.join(dir, `mods/${name}.jar`))
    }
    writeJson(path.join(dir, 'config/polymer/auto-host.json'), { enabled: true, required: false, mod_override: false, resource_pack_status_dialog: false, allow_joining_after_generation_failure: false })
    const eula = fs.readFileSync(verify(manifest.eula), 'utf8')
    assert.match(eula, /^eula=true\s*$/m)
    fs.writeFileSync(path.join(dir, 'eula.txt'), eula)
    const game = await rig.port(), rconPort = await rig.port()
    const password = crypto.randomBytes(24).toString('hex')
    const props = { 'server-ip': '127.0.0.1', 'server-port': game.port, 'online-mode': false, 'enable-rcon': true,
      'rcon.port': rconPort.port, 'rcon.password': password, 'enable-query': false, 'enable-status': false,
      'pause-when-empty-seconds': 0, 'spawn-protection': 0, difficulty: 'peaceful', gamemode: 'creative',
      'allow-flight': true, 'view-distance': 2, 'simulation-distance': 2, 'level-seed': manifest.seed,
      'white-list': false, // 26.3 defaults white-list=true; non-op test clients must not be kicked
      'level-type': 'minecraft:flat', 'generator-settings': JSON.stringify({ layers: [{ block: 'minecraft:bedrock', height: 1 }], biome: 'minecraft:plains', features: false, lakes: false }),
      'generate-structures': false, 'enforce-secure-profile': false }
    const propsFile = path.join(dir, 'server.properties')
    fs.writeFileSync(propsFile, Object.entries(props).map(([k,v]) => `${k}=${v}`).join('\n'))
    writeJson(path.join(dir, 'ops.json'), ['polymerone', 'polymertwo'].map(name => ({ name, uuid: offlineUuid(name), level: 4, bypassesPlayerLimit: false })))
    game.reservation.close(); rconPort.reservation.close()
    const send = async command => { const answer = await rcon.send(command); records.push({ command, answer }); return answer }
    server = rig.spawn('server-first', 'java', ['-Xmx3G', `-Dclaude.scratch=fawe-${rig.token}`,
      '-Dfawe.experimental.coordinated=true', `-Dpolymer.bulk.output=${rig.root}`,
      '--enable-native-access=ALL-UNNAMED', '-cp', classpath(manifest, 'server'), 'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui'], { cwd: dir })
    await rig.wait('server boot', () => fs.readFileSync(server.log, 'utf8').includes('Done ('), 180000, [server])
    await rig.wait('RCON', async () => { try { rcon = await Rcon.connect({ host: '127.0.0.1', port: rconPort.port, password }); return true } catch (e) { if (e.code !== 'ECONNREFUSED') throw e; return false } }, 30000, [server])
    await send('forceload add 3200 3200 3263 3215')

    const one = await client(rig, game.port, 'polymerone')
    await send('tp polymerone 3205 81 3205 135 20')
    const two = await client(rig, game.port, 'polymertwo')
    await send('tp polymertwo 3205 81 3205 135 20')
    // Vanilla spam debt pacing for player chat commands, matching the FAWE S6 harness.
    let lastCommandTick = -21
    const clock = async () => {
      const answer = await send('time query gametime')
      const match = answer.match(/^The game time is (\d+) tick\(s\)$/)
      assert.ok(match, `Unexpected tick clock: ${answer}`)
      return Number(match[1])
    }
    const chat = async (command, response) => {
      await rig.wait('command pacing', async () => await clock() >= lastCommandTick + 21, 15000, [one.entry])
      lastCommandTick = await clock()
      const offset = fs.statSync(one.entry.log).size
      await one.send({ action: 'command', command })
      if (response) await rig.wait(`command response ${command}`, () => response.test(fs.readFileSync(one.entry.log, 'utf8').slice(offset)), 30000, [one.entry])
    }
    // Content: two ticking-capable holder blocks and one mapped lamp, all vanilla server states.
    for (const [x, y, z, kind] of CELLS) {
      const block = kind === 'lamp' ? LAMP : 'minecraft:' + kind + '_log'
      await chat(`setblock ${x} ${y} ${z} ${block}`)
      await rig.wait(`setblock ${kind} placed`, async () =>
        (await send(`execute if block ${x} ${y} ${z} ${block}`)) === 'Test passed', 15000, [one.entry])
    }
    await rig.wait('content visible to one', async () => {
      const s = await one.send({ action: 'snapshot' })
      return s.block === 'minecraft:red_wool' && s.blockLightAbove === 14
    }, 30000, [one.entry])
    await rig.wait('content visible to two', async () => {
      const s = await two.send({ action: 'snapshot' })
      return s.block === 'minecraft:blue_wool' && s.blockLightAbove === 14
    }, 30000, [two.entry])
    const before = { one: await one.send({ action: 'snapshot' }), two: await two.send({ action: 'snapshot' }) }
    assert.equal(before.one.block, 'minecraft:red_wool')   // lamp mapped per no-pack recipient
    assert.equal(before.two.block, 'minecraft:blue_wool')  // lamp mapped per pack recipient
    assert.equal(before.one.blockLightAbove, 14)
    assert.equal(before.two.blockLightAbove, 14)
    assert.equal(before.one.displays.length, 2)            // oak + birch holders
    assert.equal(before.two.displays.length, 2)

    await chat('/pos1 3200,80,3200', /First position set/)
    await chat('/pos2 3202,80,3200', /Second position set/)
    await chat('fawe-coordinated set stone', /Coordinated edit complete: 3 changes/)
    await rig.wait('stone visible to both', async () =>
      (await one.send({ action: 'snapshot' })).block === 'minecraft:stone'
      && (await two.send({ action: 'snapshot' })).block === 'minecraft:stone', 30000, [one.entry, two.entry])
    const stone = { one: await one.send({ action: 'snapshot' }), two: await two.send({ action: 'snapshot' }) }
    assert.equal(stone.one.block, 'minecraft:stone')
    assert.equal(stone.two.block, 'minecraft:stone')
    assert.equal(stone.one.displays.length, 0)             // holders detached by the bridge
    assert.equal(stone.two.displays.length, 0)
    await rig.wait('stone light settled', async () => {
      const s = await one.send({ action: 'snapshot' })
      return s.blockLightAbove === 0 && (await two.send({ action: 'snapshot' })).blockLightAbove === 0
    }, 30000)

    await chat('fawe-coordinated set oak_log', /Coordinated edit complete: 3 changes/)
    await rig.wait('oak visible to both', async () =>
      (await one.send({ action: 'snapshot' })).block === 'minecraft:oak_log'
      && (await two.send({ action: 'snapshot' })).block === 'minecraft:oak_log', 30000, [one.entry, two.entry])
    const oak = { one: await one.send({ action: 'snapshot' }), two: await two.send({ action: 'snapshot' }) }
    assert.equal(oak.one.block, 'minecraft:oak_log')
    assert.equal(oak.two.block, 'minecraft:oak_log')
    assert.equal(oak.one.displays.length, 3)               // fresh holders for every oak cell
    assert.equal(oak.two.displays.length, 3)
    assert.ok(oak.one.displays.every(id => !before.one.displays.includes(id)))  // created once, new identities

    // Identity retention: same-block property change keeps the live holder (keepElementHolder).
    await chat('fawe-coordinated set stripped_oak_log', /Coordinated edit complete: 3 changes/)
    await rig.wait('stripped oak visible to both', async () =>
      (await one.send({ action: 'snapshot' })).block === 'minecraft:stripped_oak_log'
      && (await two.send({ action: 'snapshot' })).block === 'minecraft:stripped_oak_log', 30000, [one.entry, two.entry])
    const kept = { one: await one.send({ action: 'snapshot' }), two: await two.send({ action: 'snapshot' }) }
    assert.equal(kept.one.block, 'minecraft:stripped_oak_log')
    assert.equal(kept.two.block, 'minecraft:stripped_oak_log')
    assert.deepEqual([...kept.one.displays].sort(), [...oak.one.displays].sort())  // same holder identities

    // FAWE-side route truth: every edit ran as a direct-section publication, no WNA fallback writes.
    // relit is informational: units without light sources may legitimately skip relighting.
    const stats = JSON.parse(await send('fawepublish stats'))
    assert.equal(stats.sections, 3)
    assert.equal(stats.fallbackWrites, 0)
    assert.ok(stats.sent >= 3)
    report.routeStats = stats

    // Quarantine variant first: bulktest run quarantines its failureChunk at origin+160 (chunk
    // 210,200). Keep that chunk loaded so the refusal reaches the quarantine check rather than
    // unload identity. Must precede the manual-attachment phase: run() prepares chunk 200,200 and
    // would refuse on the manual attachment; the manual attachment persists until server stop.
    assert.match(await send('bulktest run'), /bulk-ok run/)
    await send('forceload add 3360 3200 3375 3215')
    await chat('/pos1 3360,80,3200', /First position set|Position already set/)
    await chat('/pos2 3360,80,3200', /Second position set|Position already set/)
    await chat('fawe-coordinated set stone',
      /Coordinated edit REFUSED for chunk 210,200; no changes were written[\s\S]*Chunk already reserved or quarantined/)
    assert.equal(await send('execute if block 3360 80 3200 minecraft:oak_log'), 'Test passed')
    const quarantined = JSON.parse(await send('fawepublish stats'))
    assert.equal(quarantined.sections, 3)
    assert.equal(quarantined.fallbackWrites, 0)
    report.quarantineStats = quarantined

    // Refusal: a manual attachment makes the VE participant refuse the direct route. The edit is
    // refused before any write, the world is unchanged, and no section is installed. bulktest run
    // left origin=oak_log and an unlit lamp at the probe cell; re-select after the quarantine phase.
    assert.match(await send('bulktest manualattach'), /bulk-ok manualattach/)
    await chat('/pos1 3200,80,3200', /First position set|Position already set/)
    await chat('/pos2 3202,80,3200', /Second position set|Position already set/)
    await chat('fawe-coordinated set stone',
      /Coordinated edit REFUSED for chunk 200,200; no changes were written[\s\S]*Manual chunk attachments/)
    assert.equal(await send('execute if block 3200 80 3200 minecraft:stripped_oak_log'), 'Test passed')
    assert.equal(await send('execute if block 3201 80 3200 minecraft:redstone_lamp'), 'Test passed')
    assert.equal(await send('execute if block 3202 80 3200 minecraft:oak_log'), 'Test passed')
    const refused = JSON.parse(await send('fawepublish stats'))
    assert.equal(refused.sections, 3)
    assert.equal(refused.fallbackWrites, 0)
    report.refusalStats = refused

    report.observations = { before, stone, oak, kept }
    await rig.stop(one.entry)
    await rig.stop(two.entry)
    await send('stop'); await rig.wait('server exit', () => server.child.exitCode !== null, 30000)
    rcon.close(); rcon = null
    fs.writeFileSync(propsFile, fs.readFileSync(propsFile, 'utf8').replace(/^rcon.password=.*$/m, 'rcon.password=REDACTED'))
    report.status = 'passed-subset'
  } catch (error) { failure = error; report.failure = error.stack; report.status = 'failed' } finally {
    if (rcon) { try { await rcon.send('stop') } catch {} rcon.close() }
    if (rig) {
      writeJson(path.join(rig.root, 'rcon-observations.json'), records)
      try { await rig.close() } catch (error) { failure ||= error; report.cleanupFailure = error.stack }
      report.resources = rig.resourceReport()
      report.logScan = rig.children.filter(entry => !entry.name.startsWith('display-')).map(entry => ({
        log: entry.log,
        errors: fs.readFileSync(entry.log, 'utf8').split('\n').filter(line => /\bERROR\b|Mixin apply failed|InvalidInjectionException|Failed to handle packet/.test(line))
      }))
      if (report.logScan.some(entry => entry.errors.length)) {
        failure ||= new Error('Runtime error log scan failed')
        report.logScanFailure = failure.message
        report.status = 'failed'
      }
      const props = path.join(rig.root, 'server/server.properties')
      if (fs.existsSync(props)) fs.writeFileSync(props, fs.readFileSync(props, 'utf8').replace(/^rcon.password=.*$/m, 'rcon.password=REDACTED'))
    }
    lease?.close(); report.leaseReleased = !lease || lease.closed
    writeJson(reportFile, report); console.log(reportFile)
  }
  if (failure) throw failure
}
main().catch(error => { console.error(error.stack); process.exitCode = 1 })
