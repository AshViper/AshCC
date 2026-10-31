// Simulates lua/uav.lua against world.lua: our own ship (radar, 3 lasers, anti-gravity core) starts on the ground at
// home; Raider (hostile, no IFF) crosses towards home, Friend sends our IFF code, Parked has no IFF but does not move,
// Red sends another IFF code and moves slowly, Far is past the fence. Setup: npm install (fengari) or reuse ../aa_sim.
//   node run.js <uav.lua> [ticks]
// Env: KEYS="600:h,800:h" (key presses at ticks; q is pressed at the end)  ARGS="--hold"
const path=require('path');
const {lua,lauxlib,lualib,to_luastring}=require(require.resolve('fengari',{paths:[__dirname,path.join(__dirname,'../aa_sim')]}));
const fs=require('fs');
const [,,prog,ticks]=process.argv;
const keyCodes={space:57,h:35,r:19,e:18,f:33,c:46,q:16};
const keyList=(process.env.KEYS||"").split(",").filter(Boolean).map(s=>{const [t,k]=s.split(":");return `{${Number(t)},${keyCodes[k]}}`}).join(",");
const L=lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
const code=`DEBUGL="" DEBUGT=0
local W = assert(load(${JSON.stringify(fs.readFileSync(__dirname+'/world.lua','utf8'))}, "world"))()
local prog = assert(load(${JSON.stringify(fs.readFileSync(prog,'utf8'))}, "uav"))
local main = coroutine.create(prog)
local function resume(...)
  local ok, e = coroutine.resume(main, ...)
  if not ok then error("program error at tick " .. W.tick .. ": " .. tostring(e), 0) end
end
resume(${(process.env.ARGS||"").split(" ").filter(Boolean).map(a=>JSON.stringify(a)).join(",")})
W.armedNow = true
local N = ${Number(ticks)||2400}
local presses = { ${keyList} }
local held
for t = 1, N + 40 do
  W.step()
  for _, p in ipairs(presses) do if p[1] == t then W.queue[#W.queue + 1] = { "key", p[2] } end end
  if t == N then W.queue[#W.queue + 1] = { "key", 16 }; held = t end
  local q = W.queue; W.queue = {}
  for _, ev in ipairs(q) do if coroutine.status(main) ~= "dead" then resume(table.unpack(ev)) end end
  if coroutine.status(main) == "dead" then break end
  resume("mock_tick")
  if t % 100 == 0 then
    local s = {}
    for _, L in ipairs(W.lasers) do s[#s+1] = L.firing and "F" or "-" end
    local c = W.core.cmd
    local dS = -1
    for _, sh in ipairs(W.ships) do if sh.name == "Stopper" then local x, y, z = W.shipPos(sh); dS = math.sqrt((x - W.radar.x) ^ 2 + (y - W.radar.y) ^ 2 + (z - W.radar.z) ^ 2) end end
    io.write(("[Stopper %4.0f] "):format(dS))
    io.write(("t=%5.1fs pos %7.1f %6.1f %7.1f  cmd %5.1f %5.1f %5.1f yaw %6.1f pitch %+5.1f roll %+5.1f  lasers %s"):format(t/20,
      W.radar.x, W.radar.y, W.radar.z, c[1], c[2], c[3], W.core.yaw, W.core.pitch or 0, W.core.roll or 0, table.concat(s)) .. string.char(10))
  end
end
local out = {}
out[#out+1] = "program " .. coroutine.status(main) .. " at tick " .. W.tick .. ", core holding after quit: " .. tostring(W.core.holding)
out[#out+1] = "lasers still firing after quit: " .. tostring(W.lasers[1].fire ~= 0 or W.lasers[2].fire ~= 0 or W.lasers[3].fire ~= 0)
for k, v in pairs(W.shipHits) do out[#out+1] = ("firing ticks on %-7s %d, blocks burnt %d%s"):format(k, v, W.burntShips[k] or 0, (W.destroyed and W.destroyed[k]) and (" DESTROYED at " .. W.destroyed[k]/20 .. "s") or "") end
out[#out+1] = ("ground hits %d, lowest y %.1f, furthest from home %.0f, velocity commands %d, turns %d"):format(W.stats.ground, W.minY, W.maxHome, W.cmds or 0, W.turns or 0)
out[#out+1] = ("sideslip mean %.1f max %.1f deg, slowest after 10 s %.1f b/s"):format((W.slipSum or 0) / math.max(W.slipN or 1, 1), W.slipMax or 0, W.minAirSpeed or 0)
out[#out+1] = ("terrain scans %d, lowest clearance over the terrain %.1f"):format(W.terrainScans or 0, W.minClear or 0)
out[#out+1] = "firing on a dead (stopped) ship: " .. (W.stats.deadFire or 0)
out[#out+1] = ("bank with the turn %d, against it %d, max roll %.1f, max pitch %.1f"):format(W.bankOk or 0, W.bankBad or 0, W.maxRoll or 0, W.maxPitch or 0)
out[#out+1] = "learned brain file: " .. (FILES["uav_brain.dat"] and (#FILES["uav_brain.dat"] .. " bytes") or "none")
out[#out+1] = "---- uav.log"
out[#out+1] = FILES["uav.log"] or ""
return table.concat(out, string.char(10))
`;
if(lauxlib.luaL_loadstring(L,to_luastring(code))!==0){console.log('load err',lua.lua_tojsstring(L,-1));process.exit(1)}
if(lua.lua_pcall(L,0,1,0)!==0){console.log('run err',lua.lua_tojsstring(L,-1));process.exit(1)}
console.log(lua.lua_tojsstring(L,-1));
