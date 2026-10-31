// Checks that the AI core in lua/uav.lua (between "AI CORE BEGIN" and "AI CORE END") computes the same as common.js
// and nn.js: aircraft step, safety layer, observation and network output on random states.
//   node parity.js [brain.json]
'use strict';
const fs = require('fs');
const path = require('path');
const { lua, lauxlib, lualib, to_luastring } = require(require.resolve('fengari', { paths: [__dirname, path.join(__dirname, '../aa_sim')] }));
const C = require('./common');
const { MLP } = require('./nn');

const src = fs.readFileSync(path.join(__dirname, '../../lua/uav.lua'), 'utf8');
const core = src.slice(src.indexOf('-- AI CORE BEGIN'), src.indexOf('-- AI CORE END'));
const net = process.argv[2] ? MLP.fromJSON(JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))) : new MLP([C.N_IN, 64, 64, C.N_OUT], 3);

const hJS = (x, z) => 64 + 25 * Math.sin(x / 37) * Math.cos(z / 53) + 10 * Math.sin((x + z) / 19);
const r = (() => { let s = 12345; return () => { s = (s * 1103515245 + 12345) % 2147483648; return s / 2147483648; }; })();
const cases = [];
for (let i = 0; i < 300; i++) {
  const me = { x: (r() - 0.5) * 800, y: 50 + r() * 120, z: (r() - 0.5) * 800, vx: (r() - 0.5) * 50, vy: (r() - 0.5) * 20, vz: (r() - 0.5) * 50 };
  const A = { speed: r() * 32, yaw: r() * 360 - 180, climb: (r() - 0.5) * 60, turn: (r() - 0.5) * 120 };
  const slots = [];
  for (let k = 0; k < Math.floor(r() * 3); k++) slots.push({ x: me.x + (r() - 0.5) * 600, y: me.y + (r() - 0.5) * 150, z: me.z + (r() - 0.5) * 600, vx: (r() - 0.5) * 40, vy: (r() - 0.5) * 10, vz: (r() - 0.5) * 40, hp: r() });
  const ctx = { home: { x: (r() - 0.5) * 100, y: 64 + r() * 10, z: (r() - 0.5) * 100 }, heat: r(), hp: r(), prev: [C.BANKS[i % 5], C.CLIMBS[i % 3], i % 3] };
  const cmd = [(r() - 0.5) * 140, (r() - 0.5) * 80, r() * 35];
  cases.push({ me, A, slots, ctx, cmd });
}

// JS results
const js = cases.map(c => {
  const A = { ...c.A };
  const f = C.features(c.me, A, hJS, c.slots, c.ctx);
  const q = Array.from(net.forward(f));
  const safe = C.safety(c.me, A, hJS, c.ctx.home);
  const step = C.airStep(A, c.cmd[0], c.cmd[1], c.cmd[2], 0.1);
  return { f, q, safe: safe ? safe.slice(0, 3).concat([safe[3]]) : null, step, A };
});

// Lua results
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
const cfgLua = 'CFG = {' + Object.entries(C.CFG).map(([k, v]) => `${k} = ${v}`).join(', ') + '}\n';
const toLua = v => Array.isArray(v) ? '{' + v.map(toLua).join(',') + '}' : (v && typeof v === 'object') ? '{' + Object.entries(v).map(([k, x]) => `${k}=${toLua(x)}`).join(',') + '}' : String(v);
const netLua = `{sizes=${toLua(net.sizes)},W={${net.W.map(w => toLua(Array.from(w))).join(',')}},b={${net.b.map(b => toLua(Array.from(b))).join(',')}}}`;
const prog = cfgLua + core + `
local function hAt(x, z) return 64 + 25 * math.sin(x / 37) * math.cos(z / 53) + 10 * math.sin((x + z) / 19) end
local net = ${netLua}
local cases = ${toLua(cases.map(c => ({ me: c.me, A: c.A, slots: c.slots, ctx: { home: c.ctx.home, heat: c.ctx.heat, hp: c.ctx.hp, prev: c.ctx.prev }, cmd: c.cmd })))}
local out = {}
for i, c in ipairs(cases) do
  local A = { speed = c.A.speed, yaw = c.A.yaw, climb = c.A.climb, turn = c.A.turn }
  local f = AI.features(c.me, A, hAt, c.slots, c.ctx)
  local q = AI.forward(net, f)
  local g = AI.greedy(q)
  local b, cl, sp, why = AI.safety(c.me, A, hAt, c.ctx.home)
  local vx, vy, vz, roll = AI.airStep(A, c.cmd[1], c.cmd[2], c.cmd[3], 0.1)
  out[i] = table.concat(f, ",") .. ";" .. table.concat(q, ",") .. ";" .. table.concat(g, ",") .. ";" ..
    (b and (b .. "," .. cl .. "," .. sp .. "," .. why) or "nil") .. ";" .. table.concat({ vx, vy, vz, roll, A.speed, A.yaw, A.climb, A.turn }, ",")
end
return table.concat(out, "\\n")`;
if (lauxlib.luaL_loadstring(L, to_luastring(prog)) !== 0) { console.log('load err', lua.lua_tojsstring(L, -1)); process.exit(1); }
if (lua.lua_pcall(L, 0, 1, 0) !== 0) { console.log('run err', lua.lua_tojsstring(L, -1)); process.exit(1); }
const rows = lua.lua_tojsstring(L, -1).split('\n');

const greedy = q => { const o = []; let off = 0; for (const n of C.BRANCHES) { let b = 0; for (let k = 1; k < n; k++) if (q[off + k] > q[off + b]) b = k; o.push(b); off += n; } return o; };
let worst = { f: 0, q: 0, step: 0 }, greedyBad = 0, safeBad = 0;
rows.forEach((row, i) => {
  const [f, q, g, safe, step] = row.split(';');
  const J = js[i];
  f.split(',').map(Number).forEach((v, k) => { worst.f = Math.max(worst.f, Math.abs(v - J.f[k])); });
  q.split(',').map(Number).forEach((v, k) => { worst.q = Math.max(worst.q, Math.abs(v - J.q[k])); });
  if (g !== greedy(J.q).join(',')) greedyBad++;
  const s = safe === 'nil' ? null : safe.split(',');
  if (!!s !== !!J.safe || (s && (s[3] !== J.safe[3] || s.slice(0, 3).some((v, k) => Math.abs(+v - J.safe[k]) > 1e-9)))) safeBad++;
  const st = step.split(',').map(Number), sj = J.step.concat([J.A.speed, J.A.yaw, J.A.climb, J.A.turn]);
  st.forEach((v, k) => { worst.step = Math.max(worst.step, Math.abs(v - sj[k])); });
});
const nf = js[0].f.length;
console.log(`${rows.length} states, ${nf} features (N_IN ${C.N_IN}): max |diff| features ${worst.f.toExponential(1)}, network ${worst.q.toExponential(1)}, aircraft step ${worst.step.toExponential(1)}; greedy mismatches ${greedyBad}, safety mismatches ${safeBad}`);
process.exit(worst.f < 1e-9 && worst.q < 1e-9 && worst.step < 1e-9 && !greedyBad && !safeBad && nf === C.N_IN ? 0 : 1);
