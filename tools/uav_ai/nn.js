// Small MLP (ReLU) with branching Q heads and Adam, in plain JS. lua/uav.lua runs the same network (forward and the
// online SGD step); weights are stored layer by layer as W[out][in] row-major then b[out].
'use strict';

class MLP {
  constructor(sizes, seed = 1) {
    this.sizes = sizes;
    let s = seed;
    const rand = () => { s = (s * 1103515245 + 12345) % 2147483648; return s / 2147483648; };
    this.W = []; this.b = [];
    for (let l = 0; l + 1 < sizes.length; l++) {
      const n = sizes[l], m = sizes[l + 1], sc = Math.sqrt(2 / n) * (l + 2 === sizes.length ? 0.1 : 1);
      const w = new Float64Array(m * n);
      for (let i = 0; i < w.length; i++) w[i] = (rand() * 2 - 1) * sc * Math.sqrt(3);
      this.W.push(w); this.b.push(new Float64Array(m));
    }
    this.m = this.params().map(p => new Float64Array(p.length));
    this.v = this.params().map(p => new Float64Array(p.length));
    this.t = 0;
  }
  params() { const p = []; for (let l = 0; l < this.W.length; l++) p.push(this.W[l], this.b[l]); return p; }
  copyFrom(o) { this.params().forEach((p, i) => p.set(o.params()[i])); }

  // Forward one input; keeps activations when acts is given
  forward(x, acts) {
    let a = Float64Array.from(x);
    if (acts) acts.push(a);
    for (let l = 0; l < this.W.length; l++) {
      const n = this.sizes[l], m = this.sizes[l + 1], W = this.W[l], b = this.b[l], o = new Float64Array(m);
      const last = l + 1 === this.W.length;
      for (let j = 0; j < m; j++) {
        let s = b[j]; const r = j * n;
        for (let i = 0; i < n; i++) s += W[r + i] * a[i];
        o[j] = last || s > 0 ? s : 0;
      }
      a = o;
      if (acts) acts.push(a);
    }
    return a;
  }

  // Gradient step: grads of the outputs given per sample (dOut[k] = dLoss/dOut), averaged over the batch
  train(xs, dOuts, lr, clip = 10) {
    const P = this.params(), G = P.map(p => new Float64Array(p.length));
    for (let k = 0; k < xs.length; k++) {
      const acts = [];
      this.forward(xs[k], acts);
      let d = Float64Array.from(dOuts[k]);
      for (let l = this.W.length - 1; l >= 0; l--) {
        const n = this.sizes[l], m = this.sizes[l + 1], W = this.W[l], a = acts[l], gW = G[2 * l], gb = G[2 * l + 1];
        const dn = new Float64Array(n);
        for (let j = 0; j < m; j++) {
          const dj = d[j];
          if (dj === 0) continue;
          gb[j] += dj; const r = j * n;
          for (let i = 0; i < n; i++) { gW[r + i] += dj * a[i]; dn[i] += W[r + i] * dj; }
        }
        if (l > 0) { const ap = acts[l]; for (let i = 0; i < n; i++) if (ap[i] <= 0) dn[i] = 0; }
        d = dn;
      }
    }
    // Adam with global norm clipping
    let norm = 0;
    for (const g of G) for (let i = 0; i < g.length; i++) { g[i] /= xs.length; norm += g[i] * g[i]; }
    norm = Math.sqrt(norm);
    const sc = norm > clip ? clip / norm : 1;
    this.t++;
    const b1 = 0.9, b2 = 0.999, c1 = 1 - Math.pow(b1, this.t), c2 = 1 - Math.pow(b2, this.t);
    for (let p = 0; p < P.length; p++) {
      const w = P[p], g = G[p], m = this.m[p], v = this.v[p];
      for (let i = 0; i < w.length; i++) {
        const gi = g[i] * sc;
        m[i] = b1 * m[i] + (1 - b1) * gi; v[i] = b2 * v[i] + (1 - b2) * gi * gi;
        w[i] -= lr * (m[i] / c1) / (Math.sqrt(v[i] / c2) + 1e-8);
      }
    }
  }

  toJSON() { return { sizes: this.sizes, W: this.W.map(w => Array.from(w)), b: this.b.map(b => Array.from(b)) }; }
  static fromJSON(j) { const n = new MLP(j.sizes); j.W.forEach((w, i) => n.W[i].set(w)); j.b.forEach((b, i) => n.b[i].set(b)); return n; }
}

// Branch slices of the output vector
function branchSlices(branches) {
  const s = []; let o = 0;
  for (const n of branches) { s.push([o, n]); o += n; }
  return s;
}

module.exports = { MLP, branchSlices };
