/** Interop fixture: production desktop HTTP + bridge, deterministic in-memory Core.
 * Run from this repository:
 * node --experimental-strip-types --import ../Twilight_Echo/scripts/register-renderer-aliases.mjs tools/desktop-bridge-fixture.ts
 * It never starts the desktop app, reads the user's library, or opens an audio output.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createInterface } from 'node:readline'
import { RemoteHttpServer } from '../../Twilight_Echo/src/core/cast/httpServer.ts'
import { RemoteAuthSession } from '../../Twilight_Echo/src/core/cast/auth.ts'
import { CoreRemoteService } from '../../Twilight_Echo/src/core/remote/service.ts'
import { createDefaultSettings } from '../../Twilight_Echo/src/core/settings/normalization.ts'
import { CoreDiscoveryAdvertisement } from '../../Twilight_Echo/src/main/remote/coreDiscovery.ts'
import { RemoteMediaResolver } from '../../Twilight_Echo/src/core/remote/media.ts'
import { createRemoteTlsIdentity } from '../../Twilight_Echo/src/core/cast/tlsIdentity.ts'

const pin = '123456'
const server = new RemoteHttpServer({ staticRoot: '', addresses: () => ['127.0.0.1'], auth: new RemoteAuthSession({ pin }),
  tls: createRemoteTlsIdentity('Twilight Echo Interop Fixture'),
  serverName: '书房电脑 · 联调', discovery: new CoreDiscoveryAdvertisement(),
  confirmDevice: async (request, signal) => {
    console.log(`PAIR_REQUEST ${request.id}`)
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(resolve, Number(process.env.TWILIGHT_FIXTURE_APPROVAL_DELAY_MS ?? 1000))
      signal.addEventListener('abort', () => { clearTimeout(timer); reject(new Error('cancelled')) }, { once: true })
    })
    return process.env.TWILIGHT_FIXTURE_APPROVAL === 'allow'
  } })
const settings = createDefaultSettings('win32')
const preview = `data:image/jpeg;base64,${readFileSync(new URL('../app/src/androidTest/assets/music/01.jpg', import.meta.url)).toString('base64')}`
const tracks = Array.from({ length: Number(process.env.TWILIGHT_FIXTURE_TRACK_COUNT ?? 125) }, (_, i) => ({ id: `D:/interop-private/${i}.flac`, source: 'local',
  title: i === 0 ? '来自电脑的音乐' : i === 1 ? '电脑 MP3 测试曲' : `电脑歌曲 ${i + 1}`, artist: '共享艺术家', artists: [{ name: '共享艺术家' }],
  album: '共享专辑', duration: i === 1 ? 16 : 42, format: i === 1 ? 'MP3' : 'FLAC', sampleRate: 48000, bitDepth: 16,
  bitrate: i === 1 ? 320000 : 1022519.7329376856 }))
const audioPaths = [fileURLToPath(new URL('../app/src/androidTest/assets/music/01-Golden-Hour.flac', import.meta.url)),
  fileURLToPath(new URL('./fixtures/phone-stream.mp3', import.meta.url))]
const media = new RemoteMediaResolver({ libraryId: 'lib-desktop-interop', grants: server.getMediaGrants(),
  resolve: async sourceId => {
    const index = Number(sourceId.replace(/^source-/, ''))
    return /^source-\d+$/.test(sourceId) && tracks[index]
      ? { ...tracks[index], filePath: audioPaths[index === 1 ? 1 : 0] } as never : null
  }, authorizeFile: async path => { if (!audioPaths.includes(path)) throw new Error('Not a fixture asset'); return path } })
let state = { protocolVersion: 1, epoch: 'desktop-interop', sequence: 1, phase: 'ready',
  playback: { state: 'paused', position: 0, duration: 180, volume: 0.7, playMode: 'sequential' },
  track: null, queue: null, presentationRevision: 1, libraryRevision: 3, catalogRevision: 5, entityMetadataRevision: 9 }
let receive: (message: unknown) => void
let queueTracks = []
let timer: ReturnType<typeof setTimeout> | undefined
let metadataTimer: ReturnType<typeof setTimeout> | undefined
const updateMetadata = () => { state.entityMetadataRevision++; state.catalogRevision++; publish(); console.log('METADATA_UPDATED') }
const publish = () => { state.sequence++; receive({ kind: 'state', epoch: state.epoch, sequence: state.sequence, snapshot: state }) }
const occurrence = (index: number) => `desktop-entry:${state.queue?.revision ?? 0}:${index}`
const play = (items, selected = 0, playing = true) => {
  queueTracks = items
  state.queue = { id: 'desktop-queue', revision: (state.queue?.revision ?? 0) + 1, total: items.length, index: selected, occurrenceId: '' }
  state.queue.occurrenceId = occurrence(selected)
  state.track = items[selected]; state.presentationRevision++
  state.playback.state = playing ? 'playing' : 'paused'; publish()
  const revokeAfter = Number(process.env.TWILIGHT_FIXTURE_REVOKE_AFTER_MS ?? 0)
  if (revokeAfter && !timer) timer = setTimeout(() => { remote.rotatePin(); console.log('PIN_REVOKED') }, revokeAfter)
}
const remote = new CoreRemoteService({ server, media, settings: { read: () => settings, update: patch => Object.assign(settings, patch) },
  playback: { fromPages: async (produce, options) => {
    const seeds = []; await produce(async page => { seeds.push(...page) })
    await options?.beforeAdopt?.(options.play !== false); options?.guard?.()
    play(seeds.map(seed => tracks.find(track => track.id === seed.id)), Number(options?.selectedIndex ?? 0), options.play !== false)
  } } as never,
  playlistRecords: async () => { throw new Error('Unused') }, castActive: () => false,
  resolveCover: async () => preview, failed: error => console.error(error), now: Date.now })
remote.attach({ connect: (_identity, send) => { receive = send; publish() }, disconnect() {},
  request: async (_client, request) => {
    console.log(`RPC ${request.name}`)
    let value
    const p = request.payload
    if (request.name === 'player.presentation') value = { cover: null }
    else if (request.name === 'library.tracks') {
      const found = tracks.filter(track => [track.title, track.artist, track.album].some(value => value.includes(p.search ?? '')))
      const page = found.slice(p.offset, p.offset + p.limit)
      value = { tracks: page, total: found.length, offset: p.offset, revision: 3,
        catalog: { tracks: page.map(track => ({ revision: state.catalogRevision, sourceId: `source-${tracks.indexOf(track)}`,
          total: 3, items: [{ id: 'recording-1', kind: 'recording', title: track.title, artist: track.artist },
            { id: 'artist-1', kind: 'artist', title: track.artist, artist: '' },
            { id: 'release-1', kind: 'release', title: track.album, artist: track.artist }] })) } }
    } else if (request.name === 'library.byIds') value = { tracks: p.ids.map(id => tracks.find(track => track.id === id)), revision: 3 }
    else if (request.name === 'queue.page') value = { revision: state.queue?.revision, total: queueTracks.length,
      offset: p.offset, items: queueTracks.slice(p.offset, p.offset + p.limit).map((track, i) => ({ track, position: p.offset + i, queueEntryId: occurrence(p.offset + i) })) }
    else if (request.name === 'library.artwork') value = { revision: 3, items: p.ids.map(id => ({ id, cover: 'cover://fixture.jpg' })) }
    else if (request.name === 'catalog.query') value = { revision: state.catalogRevision, offset: p.offset, total: 1, items: [{
      id: p.kind === 'artist' ? 'artist-1' : 'release-1', kind: p.kind, title: p.kind === 'artist' ? '共享艺术家' : '共享专辑', artist: '共享艺术家', sourceCount: 1 }] }
    else if (request.name === 'catalog.sources') value = { revision: state.catalogRevision, offset: p.offset, total: 1, items: [{ id: 'source-0',
      provider: 'local', recordingId: 'recording-1', releaseId: 'release-1', availability: 'indexed', inLibrary: true, track: tracks[0] }] }
    else if (request.name === 'catalog.detail') value = { revision: state.catalogRevision,
      entity: { id: p.id, kind: 'release', title: '共享专辑', artist: '共享艺术家', sourceCount: 1 }, facts: [] }
    else if (request.name === 'metadata.entity.detail') {
      const delay = Number(process.env.TWILIGHT_FIXTURE_METADATA_AFTER_MS ?? 0)
      if (delay && !metadataTimer) metadataTimer = setTimeout(updateMetadata, delay)
      value = { entityId: p.entityId, revision: state.entityMetadataRevision,
      name: state.entityMetadataRevision > 9 ? '电脑更正后的艺术家' : '整理后的共享艺术家',
      introduction: { value: '这段简介来自电脑已整理的音乐资料。', language: 'zh-Hans', origin: 'manual', attribution: null },
      facts: [], manualArtwork: { dataUrl: preview }, artworkOverride: { mode: 'value' }, artwork: [] }
    }
    else if (request.name === 'catalog.play') play([tracks[0]])
    else if (request.name === 'queue.select') { state.queue.index = p.index; state.queue.occurrenceId = occurrence(p.index); state.track = queueTracks[p.index]; publish() }
    else if (request.name === 'playback.pause' || request.name === 'playback.play') { state.playback.state = request.name.endsWith('pause') ? 'paused' : 'playing'; publish() }
    else if (request.name === 'playback.seek') { state.playback.position = p.position; publish() }
    else if (request.name === 'playback.volume') { state.playback.volume = p.volume; publish() }
    else if (request.name === 'playback.mode') { state.playback.playMode = p.mode; publish() }
    else if (request.name === 'playback.stop') { state.playback.state = 'stopped'; publish() }
    else throw new Error(`Unsupported fixture operation ${request.name}`)
    receive({ kind: 'response', requestId: request.requestId, epoch: state.epoch, ok: true, value })
  } } as never)
await remote.sync()
const status = await server.start(0)
console.log(JSON.stringify({ url: `https://127.0.0.1:${status.port}`, pin, serverId: status.serverId }))
const input = createInterface({ input: process.stdin })
input.on('line', line => {
  if (line === 'rotate') remote.rotatePin()
  if (line === 'restart') { state.epoch = `desktop-interop-${Date.now()}`; publish() }
  if (line === 'metadata') updateMetadata()
  if (line === 'exit') void stop()
})
async function stop() { if (timer) clearTimeout(timer); if (metadataTimer) clearTimeout(metadataTimer); input.close(); await remote.shutdown(); process.exit(0) }
process.on('SIGINT', () => void stop())
process.on('SIGTERM', () => void stop())
