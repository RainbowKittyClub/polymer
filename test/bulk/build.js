'use strict'

const path = require('node:path')
const fs = require('node:fs')
const { spawn } = require('node:child_process')
// Read-only reuse of the accepted S6 guard; all output goes to the scratchpad evidence tree.
const { SingleLaneLease, RESOURCE_POLICY, ResourceGuard, admitResources, admissionSample, startTime, writeJson } = require('/home/kgadberry/projects/minecraft-dev/rkc/forks/FabricAsyncWorldEdit/.claude/worktrees/wf_6f7c091d-06c-3/test/harness/rig.js')

async function main () {
  const repo = path.resolve(__dirname, '../..')
  const evidence = process.env.POLYMER_S7_EVIDENCE || '/mnt/WORKSPACE/scratchpad/fawe-s7-evidence/polymer/handoff/s7-polymer'
  fs.mkdirSync(evidence, { recursive: true })
  const recordFile = path.join(evidence, `build-${Date.now()}.json`)
  const record = { resourcePolicy: RESOURCE_POLICY, admissionSamples: [], launched: false }
  const guard = new ResourceGuard()
  let lease, timer, failure
  try {
    lease = new SingleLaneLease()
    const admission = await admitResources({ samples: record.admissionSamples })
    record.initial = admission.initial
    guard.check(record.initial)
    writeJson(recordFile, record)
    const args = ['--no-daemon', '--no-parallel', ...process.argv.slice(2)]
    record.command = [path.join(repo, 'gradlew'), ...args]
    const child = spawn(record.command[0], args, { cwd: repo, stdio: 'inherit' })
    record.launched = true
    record.pid = child.pid
    record.startTime = startTime(child.pid)
    timer = setInterval(() => {
      try { guard.check(admissionSample()) } catch (error) {
        failure ||= error
        if (startTime(child.pid) === record.startTime) child.kill('SIGTERM')
      }
    }, RESOURCE_POLICY.runtimeSampleIntervalMs)
    child.on('error', error => { failure ||= error })
    await new Promise(resolve => child.on('close', (code, signal) => {
      Object.assign(record, { code, signal })
      resolve()
    }))
  } catch (error) { failure ||= error } finally {
    clearInterval(timer)
    try {
      record.final = admissionSample()
      if (record.initial) guard.check(record.final)
    } catch (error) { failure ||= error }
    record.resources = guard.report()
    lease?.close()
    record.failure = failure?.message
    record.leaseReleased = !lease || lease.closed
    writeJson(recordFile, record)
    console.log(JSON.stringify({ resourceRecord: recordFile, ...record }))
  }
  if (failure) throw failure
  process.exitCode = record.code || (record.signal ? 1 : 0)
}
main().catch(error => { console.error(error.stack); process.exitCode = Number.isInteger(error.code) ? error.code : 1 })
