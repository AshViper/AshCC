// Simulates lua/aa_laser.lua against world.lua (3 lasers, a radar, 6 ships: bomber, hollow ring, friendly screen,
// ship behind it, low ship, ship behind terrain). Setup: npm install (fengari).
//   node run.js <aa_laser.lua> [ticks]
// Env: IFF=1 (no friends file: Friend sends IFF code BLUE, same as our own ship)
//      ARGS="--hold --spread"  BOMBERX=2 (bomber speed factor)  SELFVX/SELFVZ (our own ship's velocity)
//      DEBUGL=laser_0 DEBUGT=400 (per-tick beam trace)  DEBUGHOLD=1 (ticks fired while on hold)
// Space is pressed at tick 400 and 500, q at the end.
const {lua,lauxlib,lualib,to_luastring}=require('fengari');
const fs=require('fs');
const [,,prog,ticks]=process.argv;
const L=lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
const code=`DEBUGL=${JSON.stringify(process.env.DEBUGL||"")} DEBUGT=${Number(process.env.DEBUGT||0)} BOMBERX=${Number(process.env.BOMBERX||1)} SELFVX=${Number(process.env.SELFVX||0)} SELFVZ=${Number(process.env.SELFVZ||0)} DEBUGHOLD=${process.env.DEBUGHOLD?"true":"false"} IFF=${process.env.IFF?"true":"false"}

local W = assert(load(${JSON.stringify(fs.readFileSync(__dirname+'/world.lua','utf8'))}, "world"))()
local prog = assert(load(${JSON.stringify(fs.readFileSync(prog,'utf8'))}, "aa_laser"))
local main = coroutine.create(prog)
local function resume(...)
  local ok, e = coroutine.resume(main, ...)
  if not ok then error("program error at tick " .. W.tick .. ": " .. tostring(e), 0) end
end
resume(${(process.env.ARGS||"").split(" ").filter(Boolean).map(a=>JSON.stringify(a)).join(",")})
local startHold = ${(process.env.ARGS||"").includes("--hold")}
W.armedNow = not startHold
local N = ${Number(ticks)||1200}
local holdAt, armAt = 400, 500
for t = 1, N + 40 do
  W.step()
  if t == holdAt or t == armAt then W.queue[#W.queue + 1] = { "key", 57 } end
  -- arming counts at once, holding gets 3 ticks to switch the beams off
  if startHold then
    if t == holdAt then W.armedNow = true end
    if t == armAt + 3 then W.armedNow = false end
  else
    if t == holdAt + 3 then W.armedNow = false end
    if t == armAt then W.armedNow = true end
  end
  if t == N then W.queue[#W.queue + 1] = { "key", 16 } end
  local q = W.queue; W.queue = {}
  for _, ev in ipairs(q) do if coroutine.status(main) ~= "dead" then resume(table.unpack(ev)) end end
  if coroutine.status(main) == "dead" then break end
  resume("mock_tick")
  if t % 200 == 0 then
    local s = {}
    for _, L in ipairs(W.lasers) do s[#s+1] = ("%s %s heat=%.2f"):format(L.name, L.firing and "FIRE" or "-", L.heat) end
    io.write(("t=%5.1fs %s"):format(t/20, table.concat(s, " | ")) .. string.char(10))
  end
end
local out = {}
out[#out+1] = "program " .. coroutine.status(main) .. " at tick " .. W.tick
out[#out+1] = "windows created: " .. tostring(W.windows)
out[#out+1] = "lasers still firing after quit: " .. tostring(W.lasers[1].fire ~= 0 or W.lasers[2].fire ~= 0 or W.lasers[3].fire ~= 0)
for k, v in pairs(W.shipHits) do out[#out+1] = ("firing ticks on %-9s %d, blocks burnt %d%s"):format(k, v, W.burntShips[k] or 0, (W.destroyed and W.destroyed[k]) and (" DESTROYED at " .. W.destroyed[k]/20 .. "s") or "") end
out[#out+1] = ("ground hits %d, terrain hits %d, firing during hold %d"):format(W.stats.ground, W.stats.terrain, W.stats.holdFireTicks)
out[#out+1] = "---- aa_laser.log"
out[#out+1] = FILES["aa_laser.log"] or ""
return table.concat(out, string.char(10))
`;
if(lauxlib.luaL_loadstring(L,to_luastring(code))!==0){console.log('load err',lua.lua_tojsstring(L,-1));process.exit(1)}
if(lua.lua_pcall(L,0,1,0)!==0){console.log('run err',lua.lua_tojsstring(L,-1));process.exit(1)}
console.log(lua.lua_tojsstring(L,-1));
