// Runs the hand-written baseline on fixed seeds and prints the averages
const { Env, baseline } = require('./sim');
const N = +process.argv[2] || 200;
let R = 0, k = 0, n = 0, crash = 0, dead = 0, terr = 0, dealt = 0, taken = 0, steps = 0;
const t0 = Date.now();
for (let i = 0; i < N; i++) {
  const env = new Env(1000 + i); env.reset();
  const ctl = baseline();
  let ret = 0;
  for (;;) { const s = env.step({ control: ctl }); steps++; ret += s.reward; if (s.done || s.truncated) break; }
  R += ret; k += env.stats.kills; n += env.enemies.length; crash += env.stats.crash; dead += env.stats.dead;
  terr += env.stats.terrain; dealt += env.stats.dealt; taken += env.stats.taken;
}
console.log(`baseline ${N} eps: return ${(R/N).toFixed(1)}, kills ${k}/${n}, crash ${crash}, shot down ${dead}, terrain overrides ${(terr/N).toFixed(1)}/ep, dealt ${(dealt/N).toFixed(1)}, taken ${(taken/N).toFixed(1)}  [${steps} steps, ${((Date.now()-t0)/steps).toFixed(3)} ms/step]`);
