// Shared by the trainer and the simulator: the aircraft model, the safety layer (terrain / fence), the observation
// (features) and the action set. lua/uav.lua has the same code in Lua: keep them identical (check with parity.js).
'use strict';

const CFG = {
  maxSpeed: 30, minSpeed: 12, thrust: 8, drag: 6, gravity: 9.8, maxBank: 60, rollTime: 0.8,
  maxClimb: 30, maxDive: 35, pitchRate: 30,
  engageRange: 400, fence: 600, patrolRadius: 80, patrolHeight: 30,
  decideEvery: 0.5,       // seconds between AI decisions
  gcasTime: 3,            // seconds of flight path checked for terrain
  gcasMargin: 6,          // blocks of clearance the safety layer keeps
  threatLookahead: 3,
};

// Actions: one choice per branch
const BANKS = [-60, -30, 0, 30, 60];
const CLIMBS = [-20, 0, 20];
const SPEEDS = [16, 23, 30];
const FOCUS = [0, 1];     // 0 keep the first target slot, 1 switch to the second
const BRANCHES = [BANKS.length, CLIMBS.length, SPEEDS.length, FOCUS.length];
const N_OUT = BRANCHES.reduce((a, b) => a + b, 0);

// Terrain probes: [seconds ahead, heading offset deg]; the first is straight down
const PROBES = [[0, 0], [1, 0], [2, 0], [4, 0], [2, -35], [4, -35], [2, 35], [4, 35]];
const N_IN = 9 + PROBES.length + 2 * 11 + 3;

const rad = Math.PI / 180;
const clamp = (x, lo, hi) => x < lo ? lo : x > hi ? hi : x;
// Same as Lua's (a + 180) % 360 - 180 (Lua % is floored)
const wrap180 = a => { let r = (a + 180) % 360; if (r < 0) r += 360; return r - 180; };
const yawTo = (dx, dz) => Math.atan2(dx, -dz) / rad; // north 0, east 90

// One step of the aircraft (A = {speed, yaw, climb, turn}) under a bank / climb / speed command
function airStep(A, bankCmd, climbCmd, speedCmd, dt) {
  A.speed += clamp(clamp(speedCmd, CFG.minSpeed, CFG.maxSpeed) - A.speed, -CFG.drag * dt, CFG.thrust * dt);
  const flying = A.speed >= CFG.minSpeed * 0.8;
  const v = Math.max(A.speed, CFG.minSpeed);
  const full = Math.tan(CFG.maxBank * rad) * CFG.gravity / v / rad;
  const want = flying ? Math.tan(clamp(bankCmd, -CFG.maxBank, CFG.maxBank) * rad) * CFG.gravity / v / rad : 0;
  const step = full * dt / CFG.rollTime;
  A.turn = clamp(A.turn + clamp(want - A.turn, -step, step), -full, full);
  A.yaw = wrap180(A.yaw + A.turn * dt);
  let climb = clamp(climbCmd, -CFG.maxDive, CFG.maxClimb);
  if (!flying) climb = clamp(climb, 0, 8);
  A.climb += clamp(climb - A.climb, -CFG.pitchRate * dt, CFG.pitchRate * dt);
  const y = A.yaw * rad, c = A.climb * rad;
  const roll = Math.atan(A.turn * rad * A.speed / CFG.gravity) / rad;
  return [A.speed * Math.cos(c) * Math.sin(y), A.speed * Math.sin(c), -A.speed * Math.cos(c) * Math.cos(y), roll];
}

// Clearance above the terrain at a probe: where we will be after t seconds on heading yaw+off at the current climb
function probeClearance(me, A, heightAt, t, off) {
  const y = (A.yaw + off) * rad, sp = Math.max(A.speed, CFG.minSpeed);
  const hs = sp * Math.cos(A.climb * rad) * t;
  const px = me.x + Math.sin(y) * hs, pz = me.z - Math.cos(y) * hs;
  return me.y + sp * Math.sin(A.climb * rad) * t - heightAt(px, pz);
}

// Terrain between two points (sampled every 6 blocks): true when nothing is in the way
function lineOfSight(a, b, heightAt) {
  const dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
  const n = Math.max(1, Math.ceil(Math.sqrt(dx * dx + dz * dz) / 6));
  for (let i = 1; i < n; i++) {
    const f = i / n;
    if (a.y + dy * f < heightAt(a.x + dx * f, a.z + dz * f)) return false;
  }
  return true;
}

// The safety layer: returns [bank, climb, speed, why] overriding the AI's command, or null
//   terrain: within gcasTime on the current path the clearance drops below gcasMargin -> wings level, full climb;
//            if even a full climb would not clear it, turn towards the lower side
//   fence:   far past the fence -> turn home
function safety(me, A, heightAt, home) {
  let low = me.y - heightAt(me.x, me.z) < CFG.gcasMargin * 0.7;
  for (let t = 0.5; t <= CFG.gcasTime && !low; t += 0.5) {
    if (probeClearance(me, A, heightAt, t, 0) < CFG.gcasMargin) low = true;
  }
  if (low) {
    // Clearance a full climb would give at the end of the window
    const sp = Math.max(A.speed, CFG.minSpeed), T = CFG.gcasTime;
    const climbed = { speed: sp, yaw: A.yaw, climb: CFG.maxClimb };
    if (probeClearance(me, climbed, heightAt, T, 0) >= CFG.gcasMargin) return [0, CFG.maxClimb, CFG.maxSpeed, 'terrain'];
    const l = probeClearance(me, climbed, heightAt, T, -45), r = probeClearance(me, climbed, heightAt, T, 45);
    return [l > r ? -CFG.maxBank : CFG.maxBank, CFG.maxClimb, CFG.maxSpeed, 'terrain'];
  }
  const dh = Math.hypot(me.x - home.x, me.z - home.z);
  if (dh > CFG.fence * 1.3) {
    const err = wrap180(yawTo(home.x - me.x, home.z - me.z) - A.yaw);
    return [clamp(err, -CFG.maxBank, CFG.maxBank), 0, CFG.maxSpeed, 'fence'];
  }
  return null;
}

// Observation. me: {x,y,z,vx,vy,vz}, A: aircraft, slots: up to 2 targets {x,y,z,vx,vy,vz,hp (0..1)},
// ctx: {home, heat (0..1), hp (0..1), prev: [bank, climb, speedIdx]}
function features(me, A, heightAt, slots, ctx) {
  const f = [];
  const yaw = A.yaw * rad;
  const fwdx = Math.sin(yaw), fwdz = -Math.cos(yaw), rgtx = Math.cos(yaw), rgtz = Math.sin(yaw);
  const hx = ctx.home.x - me.x, hz = ctx.home.z - me.z, hd = Math.hypot(hx, hz);
  f.push(A.speed / CFG.maxSpeed, A.climb / 35, clamp(A.turn / 80, -1.5, 1.5), clamp(hd / CFG.fence, 0, 2),
    hd > 1 ? (hx * rgtx + hz * rgtz) / hd : 0, hd > 1 ? (hx * fwdx + hz * fwdz) / hd : 1,
    clamp((me.y - ctx.home.y - CFG.patrolHeight) / 50, -2, 2), ctx.heat, ctx.hp);
  for (const [t, off] of PROBES) f.push(clamp(probeClearance(me, A, heightAt, t, off) / 40, -1, 1.5));
  for (let i = 0; i < 2; i++) {
    const t = slots[i];
    if (!t) { for (let k = 0; k < 11; k++) f.push(0); continue; }
    const dx = t.x - me.x, dy = t.y - me.y, dz = t.z - me.z;
    const d = Math.max(Math.sqrt(dx * dx + dy * dy + dz * dz), 1), dhz = Math.max(Math.hypot(dx, dz), 1);
    const rvx = t.vx - me.vx, rvy = t.vy - me.vy, rvz = t.vz - me.vz;
    const closing = -(dx * rvx + dy * rvy + dz * rvz) / d;
    const ts = Math.sqrt(t.vx * t.vx + t.vy * t.vy + t.vz * t.vz);
    const aspect = ts > 0.5 ? -(dx * t.vx + dy * t.vy + dz * t.vz) / (d * ts) : 0;
    f.push(1, clamp(d / CFG.engageRange, 0, 2), (dx * rgtx + dz * rgtz) / dhz, (dx * fwdx + dz * fwdz) / dhz, dy / d,
      clamp(closing / 30, -2, 2), clamp(ts / 30, 0, 2), aspect, clamp(dy / 100, -2, 2), t.hp,
      lineOfSight(me, t, heightAt) ? 1 : 0);
  }
  f.push(ctx.prev[0] / 60, ctx.prev[1] / 20, ctx.prev[2] / 2);
  return f;
}

module.exports = { CFG, BANKS, CLIMBS, SPEEDS, FOCUS, BRANCHES, N_OUT, PROBES, N_IN, rad, clamp, wrap180, yawTo,
  airStep, probeClearance, lineOfSight, safety, features };
