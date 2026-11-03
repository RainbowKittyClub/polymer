'use strict'

// S7 suite: a FAWE coordinated edit must leave pack and no-pack watchers with the view an independent
// model predicts, and with the same view as the same edit made through strict vanilla commands
// (no neighbor updates, matching the coordinated route). 26.3 watchers only; Via watchers come later.

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const prior = '/home/kgadberry/projects/minecraft-dev/rkc/forks/FabricAsyncWorldEdit/.claude/worktrees/wf_6f7c091d-06c-3/test/harness/'
const { Rig, SingleLaneLease, admitResources, writeJson, json, hash, verify } = require(prior + 'rig')
const { Rcon } = require(prior + 'rcon')
const { classpath, offlineUuid } = require(prior + 'native')
const { client } = require('./combined')
const repo = path.resolve(__dirname, '../..')
const evidence = process.env.POLYMER_S7_EVIDENCE || '/mnt/WORKSPACE/scratchpad/fawe-s7-evidence/polymer/handoff/s7-polymer'

const Y = 80, Z = 3204, WIDTH = 4
const region = (name, x1) => ({ name, x1, y1: Y, z1: Z, x2: x1 + WIDTH - 1, y2: Y, z2: Z })
// 21+ blocks apart so lamp light never crosses regions; chunks 200, 201 and 203 on z chunk 200.
const A = region('coordinated', 3200)
const B = region('ordinary', 3224)
const C = region('source', 3248)
const VIEW = '3220.5 81 3210.5 180 20'
const AWAY = '7000.5 81 7000.5'
const HOLDERS = new Set(['oak_log', 'stripped_oak_log', 'birch_log', 'acacia_log', 'jungle_log', 'chest'].map(id => `minecraft:${id}`))
const LIT = 'minecraft:redstone_lamp[lit=true]'
// Chest contents never reach a vanilla client: the server checks the items, clients the block entity.
const CHEST = 'minecraft:chest[facing=north,type=single,waterlogged=false]'
const CHEST_DATA = '{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}'
// Signs have no lifecycle capability in FAWE's allowlist; used only by the unsupported-paste observation.
const SIGN = 'minecraft:oak_sign[rotation=0,waterlogged=false]'
const SIGN_DATA = '{front_text:{messages:["s7","","",""]}}'
const SEED = ['minecraft:oak_log[axis=y]', 'minecraft:birch_log[axis=y]', LIT, 'minecraft:acacia_log[axis=y]']
const SOURCE = ['minecraft:oak_log[axis=y]', 'minecraft:acacia_log[axis=y]', CHEST, LIT]
// Every cell differs from the stone it lands on, so each written flag is unambiguous.
const UNSUPPORTED = ['minecraft:oak_log[axis=y]', SIGN, 'minecraft:birch_log[axis=y]', 'minecraft:acacia_log[axis=y]']
const row = state => Array(WIDTH).fill(state)
const blockId = state => state.split('[')[0]
// The fixture maps the lamp per recipient: blue wool with the server pack, red without.
const seen = (state, pack) => blockId(state) === 'minecraft:redstone_lamp'
  ? (pack ? 'minecraft:blue_wool' : 'minecraft:red_wool') : state

async function negative (report, fault, oracle) {
  try { await oracle() } catch (error) {
    report.negativeControls.push({ fault, expectedFailure: error.message })
    return
  }
  throw new Error(`Negative control ${fault} went undetected`)
}

async function main () {
  let lease, rig, rcon, server, failure
  const records = []
  const report = { scope: 'S7 FAWE-path parity: 26.3 pack/no-pack watchers; not Via watchers, S10a or S12',
    steps: [], negativeControls: [], routeStats: {} }
  const manifestFile = path.join(__dirname, 's7-manifest.json')
  const manifest = json(manifestFile)
  report.sourceHashes = Object.fromEntries(['test/bulk/s7.js', 'test/bulk/combined.js', 'test/bulk/pin-s7.py',
    'test/bulk/s7/java/eu/pb4/polymer/bulks7/RegionSnapshotMixin.java'].map(name => [name, hash(path.join(repo, name))]))
  const reportFile = path.join(evidence, `s7-${Date.now()}.json`)
  const model = { coordinated: row('minecraft:air'), ordinary: row('minecraft:air') }
  const watchers = {}
  // Byte ranges of child logs where deliberate failures may log errors; everything else must be clean.
  const windows = []
  report.expectedErrorWindows = windows
  let one, two
  try {
    lease = new SingleLaneLease()
    rig = new Rig('/mnt/WORKSPACE/scratchpad/fawe', manifest, manifestFile, await admitResources())
    report.root = rig.root
    report.artifacts = manifest.artifacts
    const dir = path.join(rig.root, 'server')
    fs.mkdirSync(path.join(dir, 'mods'), { recursive: true })
    fs.mkdirSync(path.join(dir, 'config/polymer'), { recursive: true })
    for (const [name, key] of manifest.mods) fs.copyFileSync(verify(manifest.artifacts[key]), path.join(dir, `mods/${name}.jar`))
    writeJson(path.join(dir, 'config/polymer/auto-host.json'), { enabled: true, required: false, mod_override: false, resource_pack_status_dialog: false, allow_joining_after_generation_failure: false })
    const eula = fs.readFileSync(verify(manifest.eula), 'utf8')
    assert.match(eula, /^eula=true\s*$/m)
    fs.writeFileSync(path.join(dir, 'eula.txt'), eula)
    const game = await rig.port(), rconPort = await rig.port()
    const password = crypto.randomBytes(24).toString('hex')
    const props = { 'server-ip': '127.0.0.1', 'server-port': game.port, 'online-mode': false, 'enable-rcon': true,
      'rcon.port': rconPort.port, 'rcon.password': password, 'enable-query': false, 'enable-status': false,
      'pause-when-empty-seconds': 0, 'spawn-protection': 0, difficulty: 'peaceful', gamemode: 'creative',
      'allow-flight': true, 'view-distance': 2, 'simulation-distance': 2, 'level-seed': manifest.seed, 'white-list': false,
      'level-type': 'minecraft:flat', 'generator-settings': JSON.stringify({ layers: [{ block: 'minecraft:bedrock', height: 1 }], biome: 'minecraft:plains', features: false, lakes: false }),
      'generate-structures': false, 'enforce-secure-profile': false }
    const propsFile = path.join(dir, 'server.properties')
    fs.writeFileSync(propsFile, Object.entries(props).map(([k, v]) => `${k}=${v}`).join('\n'))
    writeJson(path.join(dir, 'ops.json'), ['polymerone', 'polymertwo'].map(name => ({ name, uuid: offlineUuid(name), level: 4, bypassesPlayerLimit: false })))
    game.reservation.close(); rconPort.reservation.close()
    const send = async command => { const answer = await rcon.send(command); records.push({ command, answer }); return answer }
    let boot = 0
    const start = async () => {
      server = rig.spawn(`server-${++boot}`, 'java', ['-Xmx3G', `-Dclaude.scratch=fawe-${rig.token}`,
        '-Dfawe.experimental.coordinated=true', `-Dpolymer.bulk.output=${rig.root}`,
        '--enable-native-access=ALL-UNNAMED', '-cp', classpath(manifest, 'server'), 'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui'], { cwd: dir })
      await rig.wait('server boot', () => fs.readFileSync(server.log, 'utf8').includes('Done ('), 180000, [server])
      await rig.wait('RCON', async () => { try { rcon = await Rcon.connect({ host: '127.0.0.1', port: rconPort.port, password }); return true } catch (e) { if (e.code !== 'ECONNREFUSED') throw e; return false } }, 30000, [server])
    }
    const stopServer = async () => {
      await send('stop'); await rig.wait('server exit', () => server.child.exitCode !== null, 60000)
      rcon.close(); rcon = null
    }
    let joins = 0
    const join = async name => {
      const label = `${name}-${++joins}`
      const gameDir = path.join(rig.root, label)
      const request = path.join(gameDir, 's7-request.json'), events = path.join(gameDir, 's7-events.jsonl')
      const native = await client(rig, game.port, name, { dir: label, mods: [['s7probe', 's7probe']],
        jvm: [`-Dpolymer.s7.request=${request}`, `-Dpolymer.s7.events=${events}`] })
      await send(`tp ${name} ${VIEW}`)
      const read = () => fs.existsSync(events) ? fs.readFileSync(events, 'utf8').split('\n').filter(Boolean).map(JSON.parse) : []
      const observe = async () => {
        const id = crypto.randomUUID()
        writeJson(request + '.new', { id, action: 'region', boxes: [A, B] }); fs.renameSync(request + '.new', request)
        return rig.wait('s7 observation', () => read().find(event => event.id === id), 30000, [native.entry])
      }
      watchers[name] = { ...native, name, pack: name === 'polymertwo', observe }
      return watchers[name]
    }
    const clock = async () => {
      const match = (await send('time query gametime')).match(/^The game time is (\d+) tick\(s\)$/)
      assert.ok(match, 'Unexpected tick clock')
      return Number(match[1])
    }
    // Player commands are paced past vanilla's chat spam debt, as in the combined gate.
    let lastCommandTick = -21
    const chat = async (command, response) => {
      await rig.wait('command pacing', async () => await clock() >= lastCommandTick + 21, 15000, [one.entry])
      lastCommandTick = await clock()
      const offset = fs.statSync(one.entry.log).size
      await one.send({ action: 'command', command })
      if (response) await rig.wait(`command response ${command}`, () => response.test(fs.readFileSync(one.entry.log, 'utf8').slice(offset)), 60000, [one.entry])
    }
    const select = async target => {
      await chat(`/pos1 ${target.x1},${Y},${Z}`, /First position set|Position already set/)
      await chat(`/pos2 ${target.x2},${Y},${Z}`, /Second position set|Position already set/)
    }
    const coordinated = async args => {
      await select(A)
      await chat(`fawe-coordinated ${args}`, /Coordinated edit complete: \d+ changes/)
    }
    const setRow = async (target, states, data = {}) => {
      for (let i = 0; i < WIDTH; i++) {
        const answer = await send(`setblock ${target.x1 + i} ${Y} ${Z} ${states[i]}${data[states[i]] || ''} strict`)
        assert.match(answer, /Changed the block|Could not set the block/)
      }
    }
    const both = states => { model.coordinated = [...states]; model.ordinary = [...states] }
    const cells = view => view.cells.map(({ at, state, light, lightAbove, blockEntity }) => ({ at, state, light, lightAbove, blockEntity }))
    const shown = view => view.displays.map(({ at, shows }) => `${at}|${shows}`).sort()
    const ids = view => view.displays.map(({ id }) => id).sort((a, b) => a - b)
    // The three oracles: server truth = model; each client's view = model mapped per recipient;
    // coordinated region = ordinary region, cell for cell and display for display.
    const verifyStep = async step => {
      assert.deepEqual(model.coordinated, model.ordinary, `${step}: model regions diverged`)
      for (const target of [A, B]) {
        for (let i = 0; i < WIDTH; i++) {
          const state = model[target.name][i]
          assert.equal(await send(`execute if block ${target.x1 + i} ${Y} ${Z} ${state}`), 'Test passed', `${step}: server ${target.name}[${i}] is not ${state}`)
          if (state === CHEST) {
            assert.match(await send(`execute if data block ${target.x1 + i} ${Y} ${Z} Items[{id:"minecraft:diamond",count:3}]`), /^Test passed/, `${step}: server ${target.name}[${i}] lost chest items`)
          }
        }
      }
      const views = {}
      for (const watcher of [one, two]) {
        const view = await watcher.observe()
        assert.ok(view.inWorld, `${step}: ${watcher.name} is not in the world`)
        const a = view.boxes.coordinated, b = view.boxes.ordinary
        assert.ok(a.loaded && b.loaded, `${step}: ${watcher.name} lacks a region chunk`)
        a.cells.forEach((cell, i) => assert.equal(cell.state, seen(model.coordinated[i], watcher.pack), `${step}: ${watcher.name} sees coordinated[${i}] as ${cell.state}`))
        const holders = model.coordinated.filter(state => HOLDERS.has(blockId(state))).length
        assert.equal(a.displays.length, holders, `${step}: ${watcher.name} sees ${a.displays.length} holder displays, expected ${holders}`)
        a.cells.forEach((cell, i) => {
          if (model.coordinated[i] === LIT) assert.equal(cell.lightAbove, 14, `${step}: ${watcher.name} lamp light at coordinated[${i}]`)
        })
        if (!model.coordinated.includes(LIT)) assert.ok(a.cells.every(cell => cell.lightAbove === 0), `${step}: ${watcher.name} stale light`)
        a.cells.forEach((cell, i) => {
          const entity = model.coordinated[i] === CHEST
          assert.equal(cell.blockEntity !== undefined, entity, `${step}: ${watcher.name} block entity presence at coordinated[${i}]`)
        })
        assert.deepEqual(cells(a), cells(b), `${step}: ${watcher.name} coordinated and ordinary cells differ`)
        assert.deepEqual(shown(a), shown(b), `${step}: ${watcher.name} coordinated and ordinary displays differ`)
        views[watcher.name] = { coordinated: a, ordinary: b }
      }
      return views
    }
    // Client chunk, light and entity packets arrive over several ticks; retry until the oracles hold.
    const settle = async (step, timeoutMs = 30000) => {
      let last
      try {
        const views = await rig.wait(`${step} settled`, async () => {
          try { return await verifyStep(step) } catch (error) { last = error; return false }
        }, timeoutMs)
        report.steps.push({ step, views })
        return views
      } catch (error) { throw last || error }
    }

    await start()
    one = await join('polymerone')
    two = await join('polymertwo')
    // Floor under each row plus a strip at z+1 where the copy/paste player stands, so the WorldEdit
    // clipboard offset never depends on whether the row itself is solid.
    for (const target of [A, B, C]) await send(`fill ${target.x1} ${Y - 1} ${Z} ${target.x2} ${Y - 1} ${Z + 1} minecraft:stone strict`)
    await setRow(C, SOURCE, { [CHEST]: CHEST_DATA })

    // Seed both regions the ordinary way: validates the oracles before any coordinated edit.
    await setRow(A, SEED); await setRow(B, SEED); both(SEED)
    await settle('seed')

    await coordinated('set stone'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:stone strict`); both(row('minecraft:stone'))
    await settle('set-stone')

    await coordinated('set oak_log'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:oak_log strict`); both(row('minecraft:oak_log[axis=y]'))
    const oak = await settle('set-oak')

    // keepElementHolder: the fixture keeps its holder across a block change, so identities survive.
    await coordinated('set stripped_oak_log'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:stripped_oak_log strict`); both(row('minecraft:stripped_oak_log[axis=y]'))
    const stripped = await settle('set-stripped')
    for (const name of ['polymerone', 'polymertwo']) {
      for (const side of ['coordinated', 'ordinary']) {
        assert.deepEqual(ids(stripped[name][side]), ids(oak[name][side]), `${name} ${side} holder identities changed`)
      }
    }

    await coordinated('replace stripped_oak_log birch_log'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:birch_log replace minecraft:stripped_oak_log strict`); both(row('minecraft:birch_log[axis=y]'))
    await settle('replace-ticking')

    await coordinated('set redstone_lamp'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:redstone_lamp strict`); both(row('minecraft:redstone_lamp[lit=false]'))
    await settle('set-lamp')

    // Paste: WorldEdit's own copy/paste (coordinated with Polymer installed) against vanilla clone.
    const stand = target => `tp polymerone ${target.x1}.5 ${Y} ${Z + 1}.5`
    const paste = async () => {
      await send(stand(C))
      await select(C)
      // FAWE reports both as "<n> blocks affected"; upstream WorldEdit says copied/pasted.
      await chat('/copy', /copied|\d+ blocks affected/)
      await send(stand(A))
      await chat('/paste', /pasted|\d+ blocks affected/)
      await send(`tp polymerone ${VIEW}`)
      assert.match(await send(`clone ${C.x1} ${Y} ${Z} ${C.x2} ${Y} ${Z} ${B.x1} ${Y} ${Z} strict`), /Successfully cloned/)
      both(SOURCE)
    }
    await paste()
    await settle('paste')
    report.routeStats.afterPaste = JSON.parse(await send('fawepublish stats'))

    await coordinated('set air'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:air strict`); both(row('minecraft:air'))
    await settle('remove')

    // Unload and reload: holders are recreated on load in both regions, with new identities.
    await paste()
    const loaded = await settle('reseed')
    for (const name of ['polymerone', 'polymertwo']) await send(`tp ${name} ${AWAY}`)
    await rig.wait('region chunks unloaded', async () =>
      await send(`execute if loaded ${A.x1} ${Y} ${Z}`) !== 'Test passed' && await send(`execute if loaded ${B.x1} ${Y} ${Z}`) !== 'Test passed', 120000, [server])
    for (const name of ['polymerone', 'polymertwo']) await send(`tp ${name} ${VIEW}`)
    const reloaded = await settle('reload', 60000)
    for (const name of ['polymerone', 'polymertwo']) {
      for (const side of ['coordinated', 'ordinary']) {
        assert.ok(ids(reloaded[name][side]).every(id => !ids(loaded[name][side]).includes(id)), `${name} ${side} kept pre-unload displays`)
      }
    }

    // Rejoin: a fresh pack client sees what the model says.
    await rig.stop(two.entry)
    two = await join('polymertwo')
    await settle('rejoin', 60000)

    // Restart: saved state reloads for fresh clients.
    report.routeStats.beforeRestart = JSON.parse(await send('fawepublish stats'))
    await rig.stop(one.entry); await rig.stop(two.entry)
    await stopServer()
    await start()
    one = await join('polymerone')
    two = await join('polymertwo')
    await settle('restart', 60000)

    // Observation, not an oracle: WorldEdit commands commit one cell at a time, so an unsupported block
    // mid-paste stops the operation after earlier cells may have been written. Record what happened.
    const marks = () => Object.fromEntries(rig.children.map(entry => [entry.log, fs.statSync(entry.log).size]))
    await coordinated('set stone'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:stone strict`); both(row('minecraft:stone'))
    await settle('before-unsupported-paste')
    await setRow(C, UNSUPPORTED, { [SIGN]: SIGN_DATA })
    const unsupported = { label: 'unsupported-paste', start: marks() }
    const chatStart = fs.statSync(one.entry.log).size
    await send(stand(C))
    await select(C)
    await chat('/copy', /copied|\d+ blocks affected/)
    await send(stand(A))
    await chat('/paste', /pasted|\d+ blocks affected|report this error|REFUSED|lifecycle/i)
    await send(`tp polymerone ${VIEW}`)
    const pasted = await clock()
    await rig.wait('unsupported paste quiet', async () => await clock() >= pasted + 40, 15000, [server])
    const written = []
    for (let i = 0; i < WIDTH; i++) written.push(await send(`execute if block ${A.x1 + i} ${Y} ${Z} ${UNSUPPORTED[i]}`) === 'Test passed')
    unsupported.end = marks()
    windows.push(unsupported)
    report.observations = { unsupportedPaste: { source: UNSUPPORTED, written,
      messages: fs.readFileSync(one.entry.log).subarray(chatStart).toString('utf8').split('\n').filter(line => line.includes('[CHAT]') && !line.includes('[Rcon:')) } }
    await setRow(C, SOURCE, { [CHEST]: CHEST_DATA })
    // Whatever the paste left behind, a coordinated edit must still succeed in that chunk.
    await coordinated('set stone'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:stone strict`); both(row('minecraft:stone'))
    await settle('after-unsupported-paste')

    // Negative control 1: the fixture ignores the recipient's pack; the independent oracle must catch it.
    await coordinated('set stone'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:stone strict`); both(row('minecraft:stone'))
    await settle('before-recipient-fault')
    assert.match(await send('bulktest recipientfault'), /bulk-ok recipientfault/)
    await coordinated('set redstone_lamp'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:redstone_lamp strict`); both(row('minecraft:redstone_lamp[lit=false]'))
    await negative(report, 'omit-recipient-pack-mapping', () => settle('recipient-fault', 10000))
    assert.match(await send('bulktest recipientrestore'), /bulk-ok recipientrestore/)
    await coordinated('set oak_log'); await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:oak_log strict`); both(row('minecraft:oak_log[axis=y]'))
    await settle('after-recipient-restore')
    report.routeStats.final = JSON.parse(await send('fawepublish stats'))

    // Negative control 2 (last: Polymer disconnects watchers of a failed token): the bridge claims
    // reconciliation without asking Polymer; Polymer's phase check must refuse to publish.
    const reconcileFault = { label: 'skip-reconcile', start: marks(), end: null }
    windows.push(reconcileFault)
    assert.match(await send('fawepublish fault-skip-reconcile true'), /"skipReconcile":true/)
    await select(A)
    await chat('fawe-coordinated set stone', /Coordinated edit/)
    await send(`fill ${B.x1} ${Y} ${Z} ${B.x2} ${Y} ${Z} minecraft:stone strict`); both(row('minecraft:stone'))
    await negative(report, 'skip-polymer-reconciliation', () => settle('reconcile-fault', 15000))
    assert.match(await send('fawepublish fault-skip-reconcile false'), /"skipReconcile":false/)
    // Detection must come from Polymer's own guard: it refuses to publish and drops every watcher.
    const faultLog = fs.readFileSync(server.log).subarray(reconcileFault.start[server.log]).toString('utf8')
    for (const name of ['polymerone', 'polymertwo']) {
      assert.ok(faultLog.includes(`${name} lost connection: Bulk edit finalization failed; chunk requires reload`),
        `${name} was not dropped by Polymer's finalization guard`)
    }
    report.negativeControls.at(-1).mechanism = 'Polymer refused to publish an unreconciled token and disconnected both watchers'

    for (const watcher of Object.values(watchers)) await rig.stop(watcher.entry)
    await stopServer()
    fs.writeFileSync(propsFile, fs.readFileSync(propsFile, 'utf8').replace(/^rcon.password=.*$/m, 'rcon.password=REDACTED'))
    assert.equal(report.negativeControls.length, 2)
    report.status = 'passed-subset'
  } catch (error) { failure = error; report.failure = error.stack; report.status = 'failed' } finally {
    if (rcon) { try { await rcon.send('stop') } catch {} rcon.close() }
    if (rig) {
      writeJson(path.join(rig.root, 'rcon-observations.json'), records)
      try { await rig.close() } catch (error) { failure ||= error; report.cleanupFailure = error.stack }
      report.resources = rig.resourceReport()
      const errors = bytes => bytes.toString('utf8').split('\n').filter(line => /\bERROR\b|Mixin apply failed|InvalidInjectionException|Failed to handle packet/.test(line))
      report.logScan = rig.children.filter(entry => !entry.name.startsWith('display-')).map(entry => {
        const bytes = fs.readFileSync(entry.log)
        const ranges = windows.filter(window => window.start[entry.log] !== undefined)
          .map(window => [window.start[entry.log], window.end?.[entry.log] ?? bytes.length, window.label])
          .sort((left, right) => left[0] - right[0])
        const outside = [], expected = {}
        let cursor = 0
        for (const [from, to, label] of ranges) {
          outside.push(...errors(bytes.subarray(cursor, from)))
          expected[label] = errors(bytes.subarray(from, to))
          cursor = Math.max(cursor, to)
        }
        outside.push(...errors(bytes.subarray(cursor)))
        return { log: entry.log, errors: outside, expected }
      })
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
