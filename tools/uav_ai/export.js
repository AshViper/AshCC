// Writes a trained network (brain.json) into lua/uav.lua between "-- BRAIN BEGIN" and "-- BRAIN END".
//   node export.js [brain.json] [../../lua/uav.lua]
'use strict';
const fs = require('fs');
const path = require('path');
const [, , src = 'brain.json', dst = path.join(__dirname, '../../lua/uav.lua')] = process.argv;
const j = JSON.parse(fs.readFileSync(src, 'utf8'));
const nums = [];
for (let l = 0; l < j.W.length; l++) { for (const v of j.W[l]) nums.push(+v.toPrecision(7)); for (const v of j.b[l]) nums.push(+v.toPrecision(7)); }
const str = j.sizes.join(' ') + '|' + nums.join(' ');
const lua = fs.readFileSync(dst, 'utf8');
const re = /(-- BRAIN BEGIN[^\n]*\n)[\s\S]*?(-- BRAIN END)/;
if (!re.test(lua)) throw new Error('no BRAIN BEGIN/END markers in ' + dst);
const ev = j.eval ? ` -- trained ${j.steps} decisions, eval return ${j.eval.ret.toFixed(1)}` : '';
fs.writeFileSync(dst, lua.replace(re, (_, a, b) => `${a}BRAIN_INIT = "${str}"${ev}\n${b}`));
console.log(`wrote ${nums.length} weights (${j.sizes.join('-')}) into ${dst}`);
