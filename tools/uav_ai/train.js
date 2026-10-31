// Trains the UAV pilot with a branching double DQN in sim.js and saves the best network (by evaluation) to brain.json.
//   node train.js [env steps, default 2000000] [out = brain.json] [--resume brain.json]
'use strict';
const fs = require('fs');
const C = require('./common');
const { Env } = require('./sim');
const { MLP, branchSlices } = require('./nn');

const argv = process.argv.slice(2);
const STEPS = +argv[0] || 2e6, OUT = argv[1] || 'brain.json';
const resume = argv.indexOf('--resume') >= 0 ? argv[argv.indexOf('--resume') + 1] : null;
const H = { envs: 32, hidden: [64, 64], gamma: 0.97, lr: 3e-4, batch: 128, updatesPerIter: 8, replay: 300000,
  warmup: 20000, epsStart: 1, epsEnd: 0.05, epsSteps: 0.4 * STEPS, targetEvery: 2000, rewardScale: 0.1,
  evalEvery: 200000, evalEpisodes: 200 };
const SL = branchSlices(C.BRANCHES);

const net = resume ? MLP.fromJSON(JSON.parse(fs.readFileSync(resume, 'utf8'))) : new MLP([C.N_IN, ...H.hidden, C.N_OUT], 7);
const tgt = new MLP(net.sizes); tgt.copyFrom(net);

const R = { obs: new Float32Array(H.replay * C.N_IN), next: new Float32Array(H.replay * C.N_IN), act: new Uint8Array(H.replay * 4),
  rew: new Float32Array(H.replay), done: new Uint8Array(H.replay), n: 0, i: 0 };
function store(o, a, r, n, d) {
  const i = R.i;
  R.obs.set(o, i * C.N_IN); if (n) R.next.set(n, i * C.N_IN);
  R.act.set(a, i * 4); R.rew[i] = r; R.done[i] = d ? 1 : 0;
  R.i = (i + 1) % H.replay; R.n = Math.min(R.n + 1, H.replay);
}

function greedy(q) { return SL.map(([o, n]) => { let b = 0; for (let k = 1; k < n; k++) if (q[o + k] > q[o + b]) b = k; return b; }); }

function update() {
  const xs = [], ds = [];
  for (let k = 0; k < H.batch; k++) {
    const i = Math.floor(Math.random() * R.n);
    const x = R.obs.subarray(i * C.N_IN, (i + 1) * C.N_IN);
    let y = R.rew[i] * H.rewardScale;
    if (!R.done[i]) {
      const nx = R.next.subarray(i * C.N_IN, (i + 1) * C.N_IN);
      const qa = net.forward(nx), qt = tgt.forward(nx), ga = greedy(qa);
      let m = 0;
      SL.forEach(([o], h) => { m += qt[o + ga[h]]; });
      y += H.gamma * m / SL.length;
    }
    const q = net.forward(x), d = new Float64Array(C.N_OUT);
    SL.forEach(([o], h) => {
      const j = o + R.act[i * 4 + h];
      d[j] = Math.max(-1, Math.min(1, q[j] - y)) / SL.length; // Huber
    });
    xs.push(x); ds.push(d);
  }
  net.train(xs, ds, H.lr);
}

function evaluate(n) {
  let ret = 0, kills = 0, foes = 0, crash = 0, dead = 0, terr = 0, dealt = 0, taken = 0;
  for (let e = 0; e < n; e++) {
    const env = new Env(1000 + e);
    let o = env.reset(), r = 0;
    for (;;) {
      const s = env.step(greedy(net.forward(o)));
      r += s.reward;
      if (s.done || s.truncated) break;
      o = s.obs;
    }
    ret += r; kills += env.stats.kills; foes += env.enemies.length; crash += env.stats.crash; dead += env.stats.dead;
    terr += env.stats.terrain; dealt += env.stats.dealt; taken += env.stats.taken;
  }
  return { ret: ret / n, kills, foes, crash, dead, terr: terr / n, dealt: dealt / n, taken: taken / n };
}

const envs = Array.from({ length: H.envs }, (_, i) => new Env(50000 + i * 7919));
const obs = envs.map(e => e.reset());
let steps = 0, updates = 0, best = -Infinity, epRet = envs.map(() => 0), recent = [];
const t0 = Date.now();
while (steps < STEPS) {
  const eps = Math.max(H.epsEnd, H.epsStart - (H.epsStart - H.epsEnd) * steps / H.epsSteps);
  for (let k = 0; k < H.envs; k++) {
    const g = greedy(net.forward(obs[k]));
    const a = g.map((b, h) => Math.random() < eps ? Math.floor(Math.random() * C.BRANCHES[h]) : b);
    const s = envs[k].step(a);
    store(obs[k], a, s.reward, s.obs, s.done);
    epRet[k] += s.reward;
    steps++;
    if (s.done || s.truncated) {
      recent.push(epRet[k]); if (recent.length > 200) recent.shift();
      epRet[k] = 0; obs[k] = envs[k].reset();
    } else obs[k] = s.obs;
  }
  if (R.n >= H.warmup) for (let u = 0; u < H.updatesPerIter; u++) {
    update(); updates++;
    if (updates % H.targetEvery === 0) tgt.copyFrom(net);
  }
  if (steps % H.evalEvery < H.envs) {
    const ev = evaluate(H.evalEpisodes);
    const avg = recent.reduce((a, b) => a + b, 0) / Math.max(recent.length, 1);
    const line = `steps ${steps} eps ${eps.toFixed(2)} train ${avg.toFixed(1)} | eval return ${ev.ret.toFixed(1)} kills ${ev.kills}/${ev.foes} crash ${ev.crash} shot down ${ev.dead} terrain ${ev.terr.toFixed(1)}/ep dealt ${ev.dealt.toFixed(1)} taken ${ev.taken.toFixed(1)} [${((Date.now() - t0) / 60000).toFixed(1)} min]`;
    console.log(line);
    if (ev.ret > best) { best = ev.ret; fs.writeFileSync(OUT, JSON.stringify({ ...net.toJSON(), eval: ev, steps })); console.log('  saved ' + OUT); }
  }
}
console.log('done, best eval return ' + best.toFixed(1));
