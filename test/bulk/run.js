'use strict'

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
const evidence = process.env.POLYMER_S7_EVIDENCE || '/mnt/WORKSPACE/scratchpad/fawe-s7-evidence/polymer/handoff/s7-polymer'
const ordinary = process.argv.includes('--ordinary')
const reference = process.argv.includes('--reference')
const focused = process.argv.includes('--focused')
const ordinaryFault = process.argv.includes('--ordinary-fault')

// Reused success oracles: deliberate faults alter real fixture behavior, never the expected value.
function mapped (snapshot, pack) {
  assert.equal(snapshot.loaded, true)
  assert.equal(snapshot.block, pack ? 'minecraft:blue_wool' : 'minecraft:red_wool')
}
function palette (snapshot) { assert.equal(snapshot.globalPaletteMatches, 512) }
function light (snapshot) { assert.equal(snapshot.blockLightAbove, 14) }
function displays (snapshot, ids) { assert.deepEqual([...snapshot.displays].sort(), [...ids].sort()) }
function ordered (packets, id) {
  const chunk = packets.findIndex(event => event.kind === 'chunk-with-light')
  const spawn = packets.findIndex(event => event.entityId === id)
  assert.ok(chunk >= 0 && spawn > chunk)
}
function negative (report, fault, oracle) {
  assert.throws(oracle, error => {
    report.negativeControls.push({ fault, expectedFailure: error.stack })
    return error instanceof assert.AssertionError
  })
}

async function ordinaryRun (rig, send, game, report) {
  const one = await client(rig, game.port, 'polymerone')
  await send('tp polymerone 3205 81 3205 135 20')
  const steps = []
  for (const operation of ['add', 'property', 'keep', 'offset', 'ticking', 'nbt', 'remove']) {
    assert.match(await send('ordinarytest ' + operation), /ordinary-ok/)
    const state = json(path.join(rig.root, `ordinary-${operation}.json`))
    let observed
    await rig.wait('ordinary observed IDs', async () => {
      observed = await one.send({ action: 'snapshot' })
      return JSON.stringify([...observed.displays].sort()) === JSON.stringify([...state.ids].sort())
    }, 30000)
    steps.push({ state, observed })
    if (operation === 'ticking') {
      await rig.wait('ordinary ticks', async () => {
        await send('ordinarytest probe')
        return json(path.join(rig.root, 'ordinary-probe.json')).ticked
      }, 15000)
      report.tickProbe = json(path.join(rig.root, 'ordinary-probe.json'))
    }
  }
  report.ordinaryObserved = steps
  const blocks = ['oak_log', 'oak_log', 'stripped_oak_log', 'acacia_log', 'birch_log', 'chest', 'air']
  const counts = [1, 1, 1, 2, 3, 4, 4]
  const detached = [0, 0, 0, 1, 2, 3, 4]
  const updates = [0, 1, 2, 2, 2, 2, 2]
  const keeps = [0, 0, 1, 1, 1, 1, 1]
  for (let i = 0; i < steps.length; i++) {
    const s = steps[i].state
    assert.equal(s.block, 'minecraft:' + blocks[i])
    assert.equal(s.created, counts[i]); assert.equal(s.attached, counts[i]); assert.equal(s.detached, detached[i])
    assert.equal(s.updates, updates[i]); assert.equal(s.keeps, keeps[i])
    assert.equal(s.axis, i === 1 ? 'x' : i < 5 ? 'y' : 'none')
    assert.equal(s.offsetY, i === 3 ? 0.25 : 0)
    assert.equal(s.shouldTick, i === 4)
    assert.equal(s.diamonds, i === 5 ? 7 : 0)
  }
  assert.deepEqual(steps[0].state.ids, steps[1].state.ids)
  assert.deepEqual(steps[1].state.ids, steps[2].state.ids)
  for (let i = 3; i < 6; i++) assert.notDeepEqual(steps[i-1].state.ids, steps[i].state.ids)
  report.ordinary = { reference, steps, normalized: steps.map(({ state }, index) => {
    const { ids, ticked, ...rest } = state
    return { ...rest, idGeneration: ids.length ? steps.findIndex(step => JSON.stringify(step.state.ids) === JSON.stringify(ids)) : null,
      ticked: index === 4 ? report.tickProbe.ticked : ticked }
  }) }
  await rig.stop(one.entry)
}

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
  return { entry, send, packets: () => fs.existsSync(path.join(dir, 'packets.jsonl')) ? fs.readFileSync(path.join(dir, 'packets.jsonl'), 'utf8').split('\n').filter(Boolean).map(JSON.parse) : [] }
}

async function main () {
  let lease, rig, rcon, server, failure
  const records = []
  // `status` carries the gate result; acceptance is a separate human verdict (acceptance-accounting.json).
  const report = { scope: ordinary ? 'ordinary installed reference/candidate differential' : focused ? 'focused API continuation' : 'Polymer API proof, not full S7 or S12', negativeControls: [] }
  const manifestFile = path.join(__dirname, 'manifest.json')
  const manifest = json(manifestFile)
  const sourcePaths = fs.readFileSync(path.join(evidence, 'changed-files.txt'), 'utf8').trim().split('\n')
  for (const entry of fs.readdirSync(__dirname, { recursive: true, withFileTypes: true })) {
    if (entry.isFile()) sourcePaths.push(path.relative(repo, path.join(entry.parentPath, entry.name)))
  }
  report.sourceHashes = Object.fromEntries([...new Set(sourcePaths)].sort().map(name => [name, hash(path.join(repo, name))]))
  const reportFile = path.join(evidence, `runtime-${Date.now()}.json`)
  try {
    lease = new SingleLaneLease()
    const admission = await admitResources()
    rig = new Rig('/mnt/WORKSPACE/scratchpad/fawe', manifest, manifestFile, admission)
    report.root = rig.root
    const dir = path.join(rig.root, 'server')
    fs.mkdirSync(path.join(dir, 'mods'), { recursive: true })
    fs.mkdirSync(path.join(dir, 'config/polymer'), { recursive: true })
    report.artifacts = manifest.artifacts
    if (reference && !ordinary) throw new Error('Reference is ordinary-only; never loads the bulk API fixture')
    // Manifest-driven mod list; legacy manifests fall back to the original three-mod layout.
    const modsList = manifest.mods || [['polymer', reference ? 'reference' : 'polymer'],
      ['fixture', ordinary ? 'ordinary' : 'fixture'], ['fabric', 'fabric']]
    for (const [name, key] of modsList) {
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
    const launch = async suffix => {
      server = rig.spawn(`server-${suffix}`, 'java', ['-Xmx3G', `-Dclaude.scratch=fawe-${rig.token}`, `-Dpolymer.bulk.output=${rig.root}`, `-Dpolymer.ordinary.destroyRetained=${ordinaryFault}`,
        '--enable-native-access=ALL-UNNAMED', '-cp', classpath(manifest, 'server'), 'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui'], { cwd: dir })
      await rig.wait('server boot', () => fs.readFileSync(server.log, 'utf8').includes('Done ('), 180000, [server])
      await rig.wait('RCON', async () => { try { rcon = await Rcon.connect({ host: '127.0.0.1', port: rconPort.port, password }); return true } catch (e) { if (e.code !== 'ECONNREFUSED') throw e; return false } }, 30000, [server])
    }
    await launch('first')
    await send('forceload add 3200 3200 3263 3215')
    if (ordinary) {
      await send('fill 3195 79 3195 3220 79 3220 minecraft:stone')
      await ordinaryRun(rig, send, game, report)
    } else {
    assert.match(await send('bulktest focused'), /bulk-ok focused/)
    report.focused = json(path.join(rig.root, 'focused.json'))
    if (!focused) {
    assert.match(await send('bulktest run'), /bulk-ok run/)
    report.server = json(path.join(rig.root, 'run.json'))
    assert.equal(report.server.failure ?? null, null)
    await send('fill 3195 79 3195 3220 79 3220 minecraft:stone')
    await send('setblock 3200 80 3200 minecraft:air')
    const one = await client(rig, game.port, 'polymerone')
    await send('tp polymerone 3205 81 3205 135 20')
    const two = await client(rig, game.port, 'polymertwo')
    await send('tp polymertwo 3205 81 3205 135 20')
    await rig.wait('first mapped cell', async () => (await one.send({ action: 'snapshot' })).block === 'minecraft:red_wool', 30000)
    await rig.wait('second mapped cell', async () => (await two.send({ action: 'snapshot' })).block === 'minecraft:blue_wool', 30000)
    const before = await one.send({ action: 'snapshot' })
    const packetStart = one.packets().length
    assert.match(await send('bulktest prepare'), /bulk-ok prepare/)
    const held = await one.send({ action: 'snapshot' })
    assert.deepEqual(held.displays.sort(), before.displays.sort())
    // Poke a block-entity setChanged (chunk-wide revision bump, as a ticking furnace would)
    // between reconciliation and publication while the token awaits lighting.
    await (async () => {
      const deadline = Date.now() + 30000
      for (;;) {
        await send('bulktest touchbe')
        if (/bulk-ok lit/.test(await send('bulktest lit'))) return
        if (Date.now() > deadline) throw new Error('Timeout waiting for native light completion')
        await new Promise(resolve => setTimeout(resolve, 400))
      }
    })()
    assert.match(await send('bulktest publish'), /bulk-ok publish/)
    let first, second
    await rig.wait('new display after publication', async () => { first = await one.send({ action: 'snapshot' }); return first.displays.length === before.displays.length + 1 }, 30000)
    second = await two.send({ action: 'snapshot' })
    assert.equal(first.block, 'minecraft:red_wool'); assert.equal(second.block, 'minecraft:blue_wool')
    assert.equal(first.globalPaletteMatches, 512); assert.equal(second.globalPaletteMatches, 512)
    await rig.wait('recipient light data', async () => {
      first = await one.send({ action: 'snapshot' }); second = await two.send({ action: 'snapshot' })
      return first.blockLightAbove === 14 && second.blockLightAbove === 14
    }, 30000)
    negative(report, 'withhold-publish-global-palette', () => palette(held))
    negative(report, 'withhold-publish-light', () => light(held))
    negative(report, 'withhold-publish-new-spawn', () => displays(held, first.displays))
    mapped(first, false); mapped(second, true); palette(first); palette(second); light(first); light(second)
    const packets = one.packets().slice(packetStart)
    const newId = first.displays.find(id => !before.displays.includes(id))
    ordered(packets, newId)
    // Replay the real trace through an out-of-order delivery fault, using the identical ordering oracle.
    const reordered = [...packets.filter(event => event.entityId === newId), ...packets.filter(event => event.entityId !== newId)]
    negative(report, 'spawn-before-chunk-delivery', () => ordered(reordered, newId))
    report.observations = { before, held, first, second, packets }
    assert.match(await send('bulktest watched'), /bulk-ok watched/)
    assert.match(await send('bulktest recipientfault'), /bulk-ok recipientfault/)
    let wrongRecipient
    await rig.wait('real incorrect pack mapping', async () => {
      wrongRecipient = await two.send({ action: 'snapshot' })
      return wrongRecipient.block === 'minecraft:red_wool'
    }, 15000)
    negative(report, 'omit-recipient-pack-mapping', () => mapped(wrongRecipient, true))
    assert.match(await send('bulktest recipientrestore'), /bulk-ok recipientrestore/)
    await rig.wait('restored recipient mapping', async () => {
      try { mapped(await two.send({ action: 'snapshot' }), true); return true } catch { return false }
    }, 15000)
    assert.match(await send('bulktest tickprepare'), /bulk-ok tickprepare/)
    const heldTick = await two.send({ action: 'snapshot' })
    assert.match(await send('bulktest tickheld'), /bulk-ok tickheld/)
    assert.match(await send('bulktest publish'), /bulk-ok publish/)
    await rig.wait('published ticking callback', async () => /bulk-ok tickpublished/.test(await send('bulktest tickpublished')), 15000)
    let afterTick
    await rig.wait('tick holder spawn', async () => {
      afterTick = await two.send({ action: 'snapshot' })
      return afterTick.displays.length === heldTick.displays.length + 1
    }, 15000)
    assert.match(await send('bulktest disconnectprepare'), /bulk-ok disconnectprepare/)
    const disconnectHeld = await two.send({ action: 'snapshot' })
    displays(disconnectHeld, afterTick.displays)
    await rig.stop(one.entry)
    await rig.wait('real pending disconnect', async () => /There are 1 of/.test(await send('list')), 15000)
    const disconnectedPackets = one.packets().length
    assert.match(await send('bulktest publish'), /bulk-ok publish/)
    let survivor
    await rig.wait('remaining client new holder', async () => {
      survivor = await two.send({ action: 'snapshot' })
      return survivor.displays.length === afterTick.displays.length + 1
    }, 15000)
    assert.equal(one.packets().length, disconnectedPackets)
    assert.match(await send('bulktest disconnectpublished'), /bulk-ok disconnectpublished/)
    report.pendingDisconnect = { held: disconnectHeld, survivor, disconnectedPackets }
    await send('save-all flush')
    await rig.stop(two.entry)
    await rig.wait('real disconnects', async () => /There are 0 of/.test(await send('list')), 15000)
    assert.match(await send('bulktest unwatched'), /bulk-ok unwatched/)
    assert.match(await send('bulktest unloadprepare'), /bulk-ok unloadprepare/)
    await send('forceload remove all')
    await rig.wait('actual pending chunk unload', async () => /bulk-ok pendingunloaded/.test(await send('bulktest pendingunloaded')), 45000)
    report.pendingUnload = json(path.join(rig.root, 'pendingunloaded.json'))
    assert.match(await send('bulktest unloaded'), /bulk-ok unloaded/)
    assert.match(await send('bulktest pendingreload'), /bulk-ok pendingreload/)
    const reloaded = json(path.join(rig.root, 'pendingreload.json'))
    // Keep the reloaded chunk resident: with no players online and no ticket it would unload again,
    // recreating holders and invalidating the recorded observedIds before the rejoin client arrives.
    await send('forceload add 3200 3200 3263 3215')
    const returning = await client(rig, game.port, 'polymerreturn')
    await send('tp polymerreturn 3205 81 3205 135 20')
    let rejoin
    await rig.wait('fresh chunk rejoin IDs', async () => {
      rejoin = await returning.send({ action: 'snapshot' })
      try { displays(rejoin, reloaded.observedIds); return true } catch { return false }
    }, 30000)
    assert.ok(rejoin.displays.every(id => !survivor.displays.includes(id)))
    report.pendingRejoin = { expected: reloaded.observedIds, observed: rejoin }
    await rig.stop(returning.entry)
    }
    }
    await send('stop'); await rig.wait('server exit', () => server.child.exitCode !== null, 30000)
    rcon.close(); rcon = null
    if (!ordinary && !focused) {
    await launch('restart')
    assert.match(await send('bulktest restart'), /bulk-ok restart/)
    report.restart = json(path.join(rig.root, 'restart.json'))
    await send('stop'); await rig.wait('restart exit', () => server.child.exitCode !== null, 30000)
    rcon.close(); rcon = null
    }
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
