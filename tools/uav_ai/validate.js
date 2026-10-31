// Compares a trained brain with the hand-written baseline on fresh seeds (not the ones used to pick the brain)
//   node validate.js [brain.json] [episodes] [first seed]
'use strict';
const fs = require('fs');
const C = require('./common');
const { Env, baseline } = require('./sim');
const { MLP, branchSlices } = require('./nn');
const [, , file = 'brain.json', N = 500, seed0 = 90000] = process.argv;
const net = MLP.fromJSON(JSON.parse(fs.readFileSync(file, 'utf8')));
const SL = branchSlices(C.BRANCHES);
const greedy = q => SL.map(([o, n]) => { let b = 0; for (let k = 1; k < n; k++) if (q[o + k] > q[o + b]) b = k; return b; });
function run(policy) {
  const s = { ret: 0, kills: 0, foes: 0, crash: 0, dead: 0, terr: 0, taken: 0, time: 0 };
  for (let i = 0; i < +N; i++) {
    const env = new Env(+seed0 + i);
    let o = env.reset(), ctl = baseline();
    for (;;) {
      const st = env.step(policy === 'ai' ? greedy(net.forward(o)) : { control: ctl });
      s.ret += st.reward;
      if (st.done || st.truncated) break;
      o = st.obs;
    }
    s.kills += env.stats.kills; s.foes += env.enemies.length; s.crash += env.stats.crash; s.dead += env.stats.dead;
    s.terr += env.stats.terrain; s.taken += env.stats.taken; s.time += env.t;
  }
  return `return ${(s.ret / N).toFixed(1)}, kills ${s.kills}/${s.foes} (${(100 * s.kills / s.foes).toFixed(1)}%), shot down ${s.dead}, crashed ${s.crash}, damage taken ${(s.taken / N).toFixed(1)}/battle, terrain warnings ${(s.terr / N).toFixed(2)}/battle, battle length ${(s.time / N).toFixed(0)} s`;
}
console.log(`${N} new battles (seeds ${seed0}..):`);
console.log('  hand-written: ' + run('base'));
console.log('  AI          : ' + run('ai'));
