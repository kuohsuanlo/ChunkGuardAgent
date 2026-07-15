// Builds a heavy target chunk for the validation harness: connects a bot and stacks a WorldEdit
// schematic of lore-filled chests many times into a single chunk, so that chunk's decompressed NBT
// is large enough to exceed a comfortable heap headroom during load (making the low-heap load
// failure deterministic). Run once against the disposable test server, then /save-all.
//
//   node build-heavy-chunk.js <originX> <originZ> <layers> [host] [port] [schematic]
//
// Requires: a Paper test server with WorldEdit + a schematic file (default "lore-chests.schem" — a
// cluster of chests with item lore; bring your own) in plugins/WorldEdit/schematics/, and the bot must be op.
// The bot connects with an older protocol; the server's ViaVersion/ViaBackwards bridges it (or use a
// natively-supported version). Adjust `version` if needed.
const mineflayer = require('mineflayer')
const OX = parseInt(process.argv[2] || '6000', 10)
const OZ = parseInt(process.argv[3] || '6000', 10)
const LAYERS = parseInt(process.argv[4] || '20', 10)
const HOST = process.argv[5] || '127.0.0.1'
const PORT = parseInt(process.argv[6] || '25565', 10)
const SCHEM = process.argv[7] || 'lore-chests.schem'
const sleep = ms => new Promise(r => setTimeout(r, ms))
const bot = mineflayer.createBot({ host: HOST, port: PORT, username: 'ChunkGuardTestBot', version: '1.21.11', auth: 'offline' })
bot.on('error', e => console.log('ERR', e.message || e))
bot.on('message', m => { const t = m.toString(); if (/error|denied|permission|large|schematic|clipboard/i.test(t)) console.log('  <srv>', t.slice(0, 140)) })
bot.once('spawn', async () => {
  console.log('spawned', bot.version)
  await sleep(1200); bot.chat('/gamemode spectator'); await sleep(800)
  bot.chat(`/tp ChunkGuardTestBot ${OX} 150 ${OZ}`); await sleep(2500)
  bot.chat(`//schem load ${SCHEM}`); await sleep(2500)
  const spots = [[0, 0], [8, 0], [0, 8], [8, 8]]
  let n = 0
  for (let ly = 0; ly < LAYERS; ly++) {
    const y = 60 + ly * 12
    for (const [dx, dz] of spots) {
      bot.chat(`/tp ChunkGuardTestBot ${OX + dx} ${y} ${OZ + dz}`); await sleep(650)
      bot.chat('//paste -a'); await sleep(1100); n++
    }
    if (ly % 4 === 3) { bot.chat('/save-all'); await sleep(1500) }
  }
  bot.chat('/save-all flush'); await sleep(5000)
  console.log(`[build] done ${n} pastes around (${OX},${OZ}); chunk is now heavy. Verify with tools/mca_read.py.`)
  bot.quit()
})
