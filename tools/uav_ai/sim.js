// Fast combat simulator for training the UAV pilot: hilly terrain, our UAV (aircraft model + safety layer + lasers)
// and hostile bombers, fighters and hovering turrets. Lasers need range and a clear line of sight (terrain hides).
'use strict';
const C = require('./common');
const { CFG, BANKS, CLIMBS, SPEEDS, clamp, wrap180, yawTo, rad } = C;

const DT = 0.1, SUB = Math.round(CFG.decideEvery / DT);
const OUR_LASERS = 3, OUR_REACH = 256, OUR_HP = 30, LASER_DPS = 2; // blocks/s per laser while hitting
const REWARD = { dealt: 1, kill: 20, taken: 1.5, crash: 50, death: 30, terrain: 1, fence: 0.5, time: 0.01 };

function rng(seed) {
  let s = seed >>> 0 || 1;
  return () => { s ^= s << 13; s >>>= 0; s ^= s >> 17; s ^= s << 5; s >>>= 0; return s / 4294967296; };
}

// Value-noise terrain: a few octaves of smoothly interpolated random lattices
function makeTerrain(r) {
  const flat = r() < 0.12, scale = flat ? 0 : 0.3 + r() * 1.4;
  const oct = [[170, 55], [70, 22], [28, 6]].map(([cell, amp]) => {
    const seed = Math.floor(r() * 1e9), m = 4096;
    const tab = new Float64Array(m);
    const rr = rng(seed);
    for (let i = 0; i < m; i++) tab[i] = rr();
    return { cell, amp: amp * scale, tab, m };
  });
  const lat = (o, i, j) => o.tab[(((i * 73856093) ^ (j * 19349663)) >>> 0) % o.m];
  const sm = t => t * t * (3 - 2 * t);
  const cache = new Map();
  return (x, z) => {
    const k = Math.round(x) * 100003 + Math.round(z);
    const c = cache.get(k);
    if (c !== undefined) return c;
    let h = 64;
    for (const o of oct) {
      if (o.amp === 0) continue;
      const fx = x / o.cell, fz = z / o.cell, i = Math.floor(fx), j = Math.floor(fz), u = sm(fx - i), v = sm(fz - j);
      const a = lat(o, i, j), b = lat(o, i + 1, j), c2 = lat(o, i, j + 1), d = lat(o, i + 1, j + 1);
      h += o.amp * ((a * (1 - u) + b * u) * (1 - v) + (c2 * (1 - u) + d * u) * v);
    }
    if (cache.size > 200000) cache.clear();
    cache.set(k, h);
    return h;
  };
}

function laserHit(d, reach) { return d > reach ? 0 : clamp(1.3 - d / reach, 0.3, 1); }

// Heat like the real lasers with the program's burst control: +0.1/s firing, -0.2/s cooling, stop 0.9, resume 0.5
function heatStep(L, firing, dt) {
  if (L.cool && L.h <= 0.5) L.cool = false;
  const on = firing && !L.cool;
  L.h = clamp(L.h + (on ? 0.1 : -0.2) * dt, 0, 1);
  if (L.h >= 0.9) L.cool = true;
  return on;
}

class Env {
  constructor(seed) { this.r = rng(seed); }

  reset(opts = {}) {
    const r = this.r;
    this.heightAt = makeTerrain(r);
    const g0 = this.heightAt(0, 0);
    this.home = { x: 0, y: g0 + 1, z: 0 };
    this.t = 0;
    const onGround = r() < 0.3;
    const A = { speed: onGround ? 0 : 18, yaw: r() * 360 - 180, climb: 0, turn: 0 };
    const me = { x: 0, y: onGround ? g0 + 1 : Math.max(g0, this.heightAt(0, 0)) + 35, z: 0, vx: 0, vy: 0, vz: 0 };
    this.me = me; this.A = A; this.hp = OUR_HP; this.airborne = !onGround;
    this.lasers = Array.from({ length: OUR_LASERS }, () => ({ h: 0, cool: false }));
    this.prev = [0, 0, 1];
    this.focus = null;
    this.enemies = [];
    const n = opts.enemies !== undefined ? opts.enemies : (r() < 0.1 ? 0 : 1 + Math.floor(r() * 3));
    for (let i = 0; i < n; i++) this.spawn(opts.kinds ? opts.kinds[i % opts.kinds.length] : ['bomber', 'fighter', 'turret'][Math.floor(r() * 3)]);
    this.stats = { dealt: 0, taken: 0, kills: 0, crash: false, dead: false, terrain: 0, fence: 0 };
    return this.observe();
  }

  spawn(kind) {
    const r = this.r, h = this.heightAt;
    const a = r() * Math.PI * 2, d = kind === 'turret' ? 120 + r() * 220 : 450 + r() * 250;
    const x = Math.cos(a) * d, z = Math.sin(a) * d;
    const e = { kind, id: this.enemies.length, x, z, vx: 0, vy: 0, vz: 0, alive: true, gone: false, lasers: [] };
    if (kind === 'bomber') {
      e.y = h(x, z) + 40 + r() * 50;
      const tx = (r() - 0.5) * 300, tz = (r() - 0.5) * 300, sp = 10 + r() * 8, l = Math.hypot(tx - x, tz - z);
      e.vx = (tx - x) / l * sp; e.vz = (tz - z) / l * sp;
      e.hp0 = 40; e.reach = 0;
    } else if (kind === 'fighter') {
      e.y = h(x, z) + 50 + r() * 40;
      e.A = { speed: 22, yaw: yawTo(-x, -z), climb: 0, turn: 0 };
      e.run = 'ATTACK'; e.hp0 = 20; e.reach = 220;
      e.lasers = [{ h: 0, cool: false }];
    } else {
      e.y = h(x, z) + 15 + r() * 30;
      e.cx = x; e.cz = z; e.ph = r() * 6.28; e.hp0 = 30; e.reach = 200;
      e.lasers = [{ h: 0, cool: false }, { h: 0, cool: false }];
    }
    e.hp = e.hp0;
    this.enemies.push(e);
  }

  // Hostiles that count: alive and on the radar (1024)
  live() { return this.enemies.filter(e => e.alive && !e.gone && Math.hypot(e.x - this.me.x, e.y - this.me.y, e.z - this.me.z) < 1024); }

  slots() {
    const me = this.me;
    const list = this.live().map(e => {
      const dx = e.x - me.x, dy = e.y - me.y, dz = e.z - me.z, d = Math.max(Math.hypot(dx, dy, dz), 1e-6);
      const closing = -(dx * (e.vx - me.vx) + dy * (e.vy - me.vy) + dz * (e.vz - me.vz)) / d;
      return { e, threat: d - CFG.threatLookahead * Math.max(0, closing) };
    }).sort((a, b) => a.threat - b.threat).map(o => o.e);
    if (this.focus !== null) {
      const i = list.findIndex(e => e.id === this.focus);
      if (i > 0) list.unshift(list.splice(i, 1)[0]);
      if (i < 0) this.focus = null;
    }
    if (this.focus === null && list.length) this.focus = list[0].id;
    return list.slice(0, 2);
  }

  observe() {
    const sl = this.slots();
    this.slotList = sl;
    const heat = this.lasers.reduce((a, L) => a + L.h, 0) / this.lasers.length;
    return C.features(this.me, this.A, this.heightAt, sl.map(e => ({ x: e.x, y: e.y, z: e.z, vx: e.vx, vy: e.vy, vz: e.vz, hp: e.hp / e.hp0 })),
      { home: this.home, heat, hp: this.hp / OUR_HP, prev: this.prev });
  }

  // a: [bank idx, climb idx, speed idx, focus idx] or {control: fn} for the hand-written baseline
  step(a) {
    const me = this.me, A = this.A, st = this.stats;
    let reward = -REWARD.time, done = false;
    if (Array.isArray(a) && a[3] === 1 && this.slotList.length > 1) this.focus = this.slotList[1].id;
    let cmd = Array.isArray(a) ? [BANKS[a[0]], CLIMBS[a[1]], SPEEDS[a[2]]] : null;
    if (Array.isArray(a)) this.prev = [cmd[0], cmd[1], a[2]];
    let terrainHit = false, fenceHit = false;
    for (let s = 0; s < SUB && !done; s++) {
      if (!Array.isArray(a)) cmd = a.control(this);
      const safe = C.safety(me, A, this.heightAt, this.home);
      const [bank, climb, speed] = safe || cmd;
      if (safe && safe[3] === 'terrain' && this.airborne) terrainHit = true;
      if (safe && safe[3] === 'fence') fenceHit = true;
      const [vx, vy, vz] = C.airStep(A, bank, climb, speed, DT);
      me.vx = vx; me.vy = vy; me.vz = vz;
      me.x += vx * DT; me.y += vy * DT; me.z += vz * DT;
      this.t += DT;
      const clear = me.y - this.heightAt(me.x, me.z);
      if (clear > 15) this.airborne = true;
      if (clear < 0.5 && this.airborne) { st.crash = true; done = true; reward -= REWARD.crash; break; }
      if (clear < 0) me.y = this.heightAt(me.x, me.z); // still on the take-off run

      for (const e of this.enemies) if (e.alive && !e.gone) this.moveEnemy(e);
      // Our lasers: the focus target first, else the nearest hostile in reach and in sight
      const cand = this.live().filter(e => this.canHit(me, e, OUR_REACH));
      let dealt = 0;
      for (const L of this.lasers) {
        let tgt = cand.find(e => e.id === this.focus) || cand.sort((p, q) => this.dist(p) - this.dist(q))[0];
        if (heatStep(L, !!tgt, DT) && tgt) {
          const dmg = LASER_DPS * laserHit(this.dist(tgt), OUR_REACH) * DT;
          tgt.hp -= dmg; dealt += dmg;
          if (tgt.hp <= 0 && tgt.alive) { tgt.alive = false; st.kills++; reward += REWARD.kill; }
        }
      }
      st.dealt += dealt; reward += REWARD.dealt * dealt;
      // Enemy lasers and collisions
      let taken = 0;
      for (const e of this.enemies) {
        if (!e.alive || e.gone) continue;
        const d = this.dist(e);
        if (d < 5) { st.crash = true; done = true; reward -= REWARD.crash; break; }
        const can = e.reach > 0 && this.canHit(e, me, e.reach);
        for (const L of e.lasers) if (heatStep(L, can, DT) && can) taken += LASER_DPS * laserHit(d, e.reach) * DT;
      }
      this.hp -= taken; st.taken += taken; reward -= REWARD.taken * taken;
      if (this.hp <= 0 && !done) { st.dead = true; done = true; reward -= REWARD.death; }
    }
    if (terrainHit) { st.terrain++; reward -= REWARD.terrain; }
    if (fenceHit) { st.fence++; reward -= REWARD.fence; }
    // Nothing to fight: stay around home at patrol height
    if (!done && this.live().filter(e => this.dist(e) < CFG.engageRange * 1.5).length === 0) {
      const dh = Math.hypot(me.x - this.home.x, me.z - this.home.z);
      reward -= 0.002 * Math.max(0, dh - CFG.patrolRadius * 1.5) + 0.002 * Math.min(Math.abs(me.y - this.home.y - CFG.patrolHeight), 50);
    }
    const left = this.enemies.some(e => e.alive && !e.gone);
    const truncated = !done && (this.t >= 150 || (!left && this.t >= 40));
    return { obs: done ? null : this.observe(), reward, done, truncated };
  }

  dist(e) { return Math.hypot(e.x - this.me.x, e.y - this.me.y, e.z - this.me.z); }
  canHit(from, to, reach) {
    const d = Math.hypot(to.x - from.x, to.y - from.y, to.z - from.z);
    return d <= reach && C.lineOfSight(from, to, this.heightAt);
  }

  moveEnemy(e) {
    const h = this.heightAt, me = this.me;
    if (e.kind === 'bomber') {
      e.x += e.vx * DT; e.z += e.vz * DT;
      const want = h(e.x + e.vx * 3, e.z + e.vz * 3) + 40;
      e.vy = clamp((want - e.y) * 0.5, -4, 4); e.y += e.vy * DT;
      if (Math.hypot(e.x, e.z) > 1100) e.gone = true;
    } else if (e.kind === 'turret') {
      e.ph += DT * 0.15;
      const nx = e.cx + Math.cos(e.ph) * 12, nz = e.cz + Math.sin(e.ph) * 12;
      e.vx = (nx - e.x) / DT; e.vz = (nz - e.z) / DT; e.vy = 0;
      e.x = nx; e.z = nz;
    } else {
      // Fighter: the hand-written boom-and-zoom against us, with the same safety layer
      const d = this.dist(e);
      if (e.run === 'ATTACK') {
        const ahead = (me.x - e.x) * Math.sin(e.A.yaw * rad) - (me.z - e.z) * Math.cos(e.A.yaw * rad);
        if (d < 25 || (ahead < 0 && d < 50)) e.run = 'EXTEND';
      } else if (d > 120) e.run = 'ATTACK';
      let P;
      if (e.run === 'ATTACK') {
        const tgo = clamp(d / Math.max(e.A.speed, 1), 0, 4);
        P = { x: me.x + me.vx * tgo, y: me.y + me.vy * tgo + 4, z: me.z + me.vz * tgo };
      } else {
        const y = e.A.yaw * rad;
        P = { x: e.x + Math.sin(y) * 100, y: e.y + 15, z: e.z - Math.cos(y) * 100 };
      }
      const cmd = steerTo(e, e.A, P, 28, 55);
      const safe = C.safety(e, e.A, h, { x: 0, z: 0 });
      const [bank, climb, speed] = safe || cmd;
      const [vx, vy, vz] = C.airStep(e.A, bank, climb, speed, DT);
      e.vx = vx; e.vy = vy; e.vz = vz;
      e.x += vx * DT; e.y += vy * DT; e.z += vz * DT;
      if (e.y < h(e.x, e.z) + 0.5) { e.alive = false; } // it crashed (no kill reward)
    }
  }
}

// Bank / climb / speed that fly towards a point the way the old hand-written program did
function steerTo(me, A, P, speed, bankLimit) {
  const dx = P.x - me.x, dy = P.y - me.y, dz = P.z - me.z, hz = Math.hypot(dx, dz);
  const v = Math.max(A.speed, CFG.minSpeed);
  const maxTurn = Math.tan(bankLimit * rad) * CFG.gravity / v / rad;
  const turn = hz > 1 ? clamp(wrap180(yawTo(dx, dz) - A.yaw) * 1.5, -maxTurn, maxTurn) : 0;
  const bank = Math.atan(turn * rad * v / CFG.gravity) / rad;
  return [bank, clamp(Math.atan2(dy, Math.max(hz, 1)) / rad, -CFG.maxDive, CFG.maxClimb), speed];
}

// The hand-written program (before the AI) as a controller: boom-and-zoom on the top threat, patrol otherwise
function baseline() {
  let run = 'ATTACK', chase = null;
  return env => {
    const me = env.me, A = env.A;
    const sl = env.slots();
    const t = sl.find(e => env.dist(e) <= CFG.engageRange);
    if (!t || t.id !== chase) run = 'ATTACK';
    chase = t ? t.id : null;
    if (t) {
      env.focus = t.id;
      const d = env.dist(t), y = A.yaw * rad;
      if (run === 'ATTACK') {
        const ahead = (t.x - me.x) * Math.sin(y) - (t.z - me.z) * Math.cos(y);
        if (d < 25 || (ahead < 0 && d < 50)) run = 'EXTEND';
      } else if (d > 120) run = 'ATTACK';
      if (run === 'ATTACK') {
        const tgo = clamp(d / Math.max(A.speed, 1), 0, 4);
        return steerTo(me, A, { x: t.x + t.vx * tgo, y: t.y + t.vy * tgo + 7, z: t.z + t.vz * tgo }, 30, 60);
      }
      return steerTo(me, A, { x: me.x + Math.sin(y) * 100, y: me.y + 15, z: me.z - Math.cos(y) * 100 }, 30, 30);
    }
    const a = Math.atan2(me.z - env.home.z, me.x - env.home.x) + Math.min(Math.PI / 2, 30 / CFG.patrolRadius);
    const alt = env.home.y + CFG.patrolHeight;
    return steerTo(me, A, { x: env.home.x + Math.cos(a) * CFG.patrolRadius, y: alt, z: env.home.z + Math.sin(a) * CFG.patrolRadius }, 16, 30);
  };
}

module.exports = { Env, baseline, rng, REWARD };
