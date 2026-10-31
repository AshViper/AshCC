// Runs lua/radar_3d.lua against world.lua and writes frames.json (the HD monitor commands per frame); open view.html
// to replay them on canvases. Setup: reuses fengari from ../aa_sim.
//   node run.js ../../lua/radar_3d.lua
const path = require('path'), fs = require('fs');
const {lua, lauxlib, lualib, to_luastring} = require(require.resolve('fengari', {paths: [path.join(__dirname, '../aa_sim')]}));
const prog = process.argv[2];
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
const code = `
local S = assert(load(${JSON.stringify(fs.readFileSync(__dirname + '/world.lua', 'utf8'))}, "world"))()
local prog = assert(load(${JSON.stringify(fs.readFileSync(prog, 'utf8'))}, "radar_3d"))
local P = table.pack
-- 2 s: drag to rotate; 4 s: north-up toggle; 6 s: height colours; 8 s: zoom in; 10 s: quit
local status = S.run(prog, {
  { 2, P("hd_touch", "m", 500, 400, 1), P("hd_drag", "m", 380, 340, 1), P("hd_release", "m", 380, 340, 1) },
  { 4.05, P("hd_touch", "m", 1024 - 3 * 50 + 10, 15, 1) },
  { 6.05, P("hd_touch", "m", 1024 - 2 * 50 + 10, 15, 1) },
  { 8.05, P("hd_scroll", "m", 500, 300, -1), P("hd_scroll", "m", 500, 300, -1) },
  { 10, P("key", 16, false) },
})
local function enc(v)
  local t = type(v)
  if t == "table" then
    local o = {}
    if #v > 0 then for _, x in ipairs(v) do o[#o + 1] = enc(x) end return "[" .. table.concat(o, ",") .. "]" end
    for k, x in pairs(v) do o[#o + 1] = string.format("%q", tostring(k)) .. ":" .. enc(x) end
    return "{" .. table.concat(o, ",") .. "}"
  elseif t == "string" then return string.format("%q", v)
  elseif t == "number" then return (v ~= v or v == math.huge or v == -math.huge) and "null" or string.format("%.2f", v)
  elseif t == "boolean" then return tostring(v) end
  return "null"
end
local out = {}
for i, f in ipairs(S.frames) do out[i] = enc(f) end
return status .. "|" .. #S.frames .. "|" .. (S.terrainScans or 0) .. "|[" .. table.concat(out, ",") .. "]"
`;
if (lauxlib.luaL_loadstring(L, to_luastring(code)) !== 0) { console.log('load err', lua.lua_tojsstring(L, -1)); process.exit(1); }
if (lua.lua_pcall(L, 0, 1, 0) !== 0) { console.log('run err', lua.lua_tojsstring(L, -1)); process.exit(1); }
const res = lua.lua_tojsstring(L, -1);
const [status, n, scans] = res.split('|', 3);
const json = res.slice(status.length + n.length + scans.length + 3);
fs.writeFileSync(__dirname + '/frames.js', 'FRAMES=' + json + ';');
console.log(`program ${status}, frames ${n}, terrain scans ${scans}`);
const frames = JSON.parse(json);
frames.forEach((f, i) => {
  const ops = {};
  f.forEach(c => ops[c[0]] = (ops[c[0]] || 0) + 1);
  console.log('frame', i, JSON.stringify(ops));
});
