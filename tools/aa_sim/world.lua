-- Mock CC + AshCC radar/laser world for aa_laser.lua (run by run.js under fengari)
local W = { tick = 0, queue = {}, stats = { ground = 0, terrain = 0, offTargetFireTicks = 0, holdFireTicks = 0 }, shipHits = {}, burntShips = {} }
SIMW = W
local GROUND = 64 -- top surface of the ground
local RADAR = { x = 0.5, y = 64.5, z = 0.5 }

---------------------------------------------------------------- ships (translate only)
local ships = {}
local function ship(id, name, ox, oy, oz, vx, vy, vz, blocks)
  ships[#ships + 1] = { id = id, name = name, o = { ox, oy, oz }, v = { vx, vy, vz }, blocks = blocks, hostile = name ~= "Friend" }
end
local function box(x0, x1, y0, y1, z0, z1, hollowCentre)
  local b = {}
  for x = x0, x1 do for y = y0, y1 do for z = z0, z1 do
    if not (hollowCentre and math.abs(x) <= 1 and math.abs(z) <= 1) then b[#b + 1] = { x, y, z } end
  end end end
  return b
end
-- approaching bomber 3x1x3, 10 b/s towards the battery
ship("aaaa-bomber", "Bomber", 70, 110, -70, -6 * (BOMBERX or 1), 0, 6 * (BOMBERX or 1), box(-1, 1, 0, 0, -1, 1))
-- 5x5 ring with an empty 3x3 centre high overhead: the centre of mass is in the hole
ship("bbbb-ring", "Ring", 3, 120, 6, 0, 0, 0, box(-2, 2, 0, 0, -2, 2, true))
-- friendly slab screening a hostile behind it (from laser_2 looking south)
ship("cccc-friend", "Friend", 0, 70, 20, 0, 0, 0, box(-3, 3, -2, 2, 0, 0))
ship("dddd-screened", "Screened", 0, 76, 45, 0, 0, 0, box(0, 0, 0, 0, 0, 0))
-- low ship skimming the ground: not an air target
ship("eeee-low", "Low", -30, 66, -20, 1, 0, 0, box(-1, 1, 0, 0, -1, 1))
-- far raider: starts ~410 m out (beyond the 256 m beam), flies in at 15 b/s
ship("gggg-far", "Far", 0, 150, -380, 0, 0, 15, box(-1, 1, 0, 0, -1, 1))
-- hostile hidden behind a terrain wall (x = 20..21, y < 82, z -6..6)
ship("ffff-hidden", "Hidden", 45, 72, 0, 0, 0, 0, box(-1, 1, 0, 0, -1, 1))

local function shipPos(s)
  local sx, sy, sz = 0, 0, 0
  for _, b in ipairs(s.blocks) do sx, sy, sz = sx + b[1], sy + b[2], sz + b[3] end
  local n = #s.blocks
  return s.o[1] + 0.5 + sx / n, s.o[2] + 0.5 + sy / n, s.o[3] + 0.5 + sz / n
end
local function shipBounds(s)
  local b = { minX = 1e9, minY = 1e9, minZ = 1e9, maxX = -1e9, maxY = -1e9, maxZ = -1e9 }
  for _, p in ipairs(s.blocks) do
    local x, y, z = s.o[1] + p[1], s.o[2] + p[2], s.o[3] + p[3]
    b.minX, b.minY, b.minZ = math.min(b.minX, x), math.min(b.minY, y), math.min(b.minZ, z)
    b.maxX, b.maxY, b.maxZ = math.max(b.maxX, x + 1), math.max(b.maxY, y + 1), math.max(b.maxZ, z + 1)
  end
  return b
end

---------------------------------------------------------------- ray casting
local function slab(o, d, lo, hi)
  if math.abs(d) < 1e-12 then
    if o < lo or o > hi then return nil end
    return -math.huge, math.huge
  end
  local t1, t2 = (lo - o) / d, (hi - o) / d
  if t1 > t2 then t1, t2 = t2, t1 end
  return t1, t2
end
local function rayBox(o, d, x0, y0, z0, x1, y1, z1)
  local a1, a2 = slab(o[1], d[1], x0, x1); if not a1 then return nil end
  local b1, b2 = slab(o[2], d[2], y0, y1); if not b1 then return nil end
  local c1, c2 = slab(o[3], d[3], z0, z1); if not c1 then return nil end
  local tn, tf = math.max(a1, b1, c1), math.min(a2, b2, c2)
  if tn > tf or tf < 0 then return nil end
  return math.max(tn, 0)
end
local RANGE = 256
local function trace(o, d)
  local best, hit = RANGE, { type = "none" }
  if d[2] < 0 then
    local t = (GROUND - o[2]) / d[2]
    if t < best then best, hit = t, { type = "block", block = "minecraft:grass_block", what = "ground" } end
  end
  local t = rayBox(o, d, 20, GROUND, -6, 22, 82, 7)
  if t and t < best then best, hit = t, { type = "block", block = "minecraft:stone", what = "terrain" } end
  for _, s in ipairs(ships) do
    for i, p in ipairs(s.blocks) do
      local x, y, z = s.o[1] + p[1], s.o[2] + p[2], s.o[3] + p[3]
      local tb = rayBox(o, d, x, y, z, x + 1, y + 1, z + 1)
      if tb and tb < best then best, hit = tb, { type = "block", block = "minecraft:iron_block", subLevel = s.id, ship = s, index = i } end
    end
  end
  hit.distance = best
  hit.x, hit.y, hit.z = o[1] + d[1] * best, o[2] + d[2] * best, o[3] + d[3] * best
  return hit
end
local function public(hit)
  if not hit then return nil end
  return { type = hit.type, block = hit.block, subLevel = hit.subLevel, distance = hit.distance, x = hit.x, y = hit.y, z = hit.z }
end

---------------------------------------------------------------- lasers
local function cross(a, b) return { a[2] * b[3] - a[3] * b[2], a[3] * b[1] - a[1] * b[3], a[1] * b[2] - a[2] * b[1] } end
local lasers = {}
-- Lasers have no front: aim frame is forward = north, up = up, right = east (yaw 0 = north, pitch 90 = up)
local function laser(name, x, y, z)
  local fwd, up = { 0, 0, -1 }, { 0, 1, 0 }
  local L = { name = name, c = { x + 0.5, y + 0.5, z + 0.5 }, fwd = fwd, up = up, right = cross(fwd, up),
    yaw = 0, pitch = 0, ty = 0, tp = 0, fire = 0, heat = 0, over = false, firing = false, burn = nil, progress = 0 }
  lasers[#lasers + 1] = L
  return L
end
laser("laser_0", 3, 64, 0)
laser("laser_1", -3, 64, 0)
laser("laser_2", 0, 64, 4)

local function dirOf(L, yaw, pitch)
  local y, p = math.rad(yaw), math.rad(pitch)
  local a, b, c = math.sin(y) * math.cos(p), math.sin(p), math.cos(y) * math.cos(p)
  return { L.right[1] * a + L.up[1] * b + L.fwd[1] * c, L.right[2] * a + L.up[2] * b + L.fwd[2] * c, L.right[3] * a + L.up[3] * b + L.fwd[3] * c }
end
local function muzzle(L, d) return { L.c[1] + d[1] * 0.6, L.c[2] + d[2] * 0.6, L.c[3] + d[3] * 0.6 } end
local function clamp(v, m) return math.max(-m, math.min(m, v)) end

local function stepLaser(L)
  -- Same as the mod: turn along the shortest arc, 9 degrees per tick
  local step = math.rad(9)
  local c, g = dirOf(L, L.yaw, L.pitch), dirOf(L, L.ty, L.tp)
  local dot = math.max(-1, math.min(1, c[1] * g[1] + c[2] * g[2] + c[3] * g[3]))
  local ang = math.acos(dot)
  if ang <= step then
    L.yaw, L.pitch = L.ty, L.tp
  else
    local n
    if ang > math.pi - 1e-6 then
      local ax = math.abs(c[2]) < 0.9 and cross(c, { 0, 1, 0 }) or cross(c, { 1, 0, 0 })
      local l = math.sqrt(ax[1] ^ 2 + ax[2] ^ 2 + ax[3] ^ 2)
      ax = { ax[1] / l, ax[2] / l, ax[3] / l }
      local p = cross(ax, c)
      n = { c[1] * math.cos(step) + p[1] * math.sin(step), c[2] * math.cos(step) + p[2] * math.sin(step), c[3] * math.cos(step) + p[3] * math.sin(step) }
    else
      local a, b = math.sin(ang - step) / math.sin(ang), math.sin(step) / math.sin(ang)
      n = { c[1] * a + g[1] * b, c[2] * a + g[2] * b, c[3] * a + g[3] * b }
    end
    local l = math.sqrt(n[1] ^ 2 + n[2] ^ 2 + n[3] ^ 2)
    n = { n[1] / l, n[2] / l, n[3] / l }
    L.pitch = math.deg(math.asin(math.max(-1, math.min(1, n[2]))))
    if n[1] ^ 2 + n[3] ^ 2 > 1e-12 then L.yaw = math.deg(math.atan(n[1], -n[3])) end
  end
  local want = L.fire ~= 0
  if want and not L.over then
    L.heat = L.heat + 0.005
    if L.heat >= 1 then L.heat, L.over = 1, true; W.queue[#W.queue + 1] = { "laser_overheat", L.name } end
  elseif L.heat > 0 then
    L.heat = math.max(0, L.heat - 0.01)
    if L.heat == 0 and L.over then L.over = false end
  end
  if L.fire > 0 then L.fire = L.fire - 1 end
  L.firing = want and not L.over
  L.hit = nil
  if not L.firing then return end
  local d = dirOf(L, L.yaw, L.pitch)
  local hit = trace(muzzle(L, d), d)
  L.hit = hit
  if not W.armedNow then
    W.stats.holdFireTicks = W.stats.holdFireTicks + 1
    if DEBUGHOLD then io.write(("hold-fire tick %d %s%s"):format(W.tick, L.name, string.char(10))) end
  end
  if hit.what == "ground" then W.stats.ground = W.stats.ground + 1 end
  if hit.what == "terrain" then W.stats.terrain = W.stats.terrain + 1 end
  if os.getenv and DEBUGL == L.name and W.tick < DEBUGT then
    io.write(("%d %s yaw=%.2f pitch=%.2f ty=%.2f tp=%.2f hit=%s %s prog=%.1f%s"):format(W.tick, L.name, L.yaw, L.pitch, L.ty, L.tp,
      hit.ship and hit.ship.name or hit.type, hit.index and tostring(hit.index) or "", L.progress, string.char(10)))
  end
  if hit.ship then
    W.shipHits[hit.ship.name] = (W.shipHits[hit.ship.name] or 0) + 1
    local key = hit.ship.id .. ":" .. hit.index
    if L.burn ~= key then L.burn, L.progress = key, 0 end
    L.progress = L.progress + 0.5
    if L.progress >= 5 then
      table.remove(hit.ship.blocks, hit.index)
      W.burntShips[hit.ship.name] = (W.burntShips[hit.ship.name] or 0) + 1
      W.queue[#W.queue + 1] = { "laser_break", L.name, "minecraft:iron_block", hit.x, hit.y, hit.z, hit.ship.id }
      L.burn = nil
    end
  else
    L.burn = nil
  end
end

local SELFV = { SELFVX or 0, 0, SELFVZ or 0 }
function W.step()
  W.tick = W.tick + 1
  -- our own platform (radar + lasers) moves too
  RADAR.x, RADAR.z = RADAR.x + SELFV[1] / 20, RADAR.z + SELFV[3] / 20
  for _, L in ipairs(lasers) do L.c[1], L.c[3] = L.c[1] + SELFV[1] / 20, L.c[3] + SELFV[3] / 20 end
  for _, L in ipairs(lasers) do stepLaser(L) end
  for i = #ships, 1, -1 do
    local s = ships[i]
    for k = 1, 3 do s.o[k] = s.o[k] + s.v[k] / 20 end
    if #s.blocks == 0 then
      W.destroyed = W.destroyed or {}
      W.destroyed[s.name] = W.tick
      table.remove(ships, i)
    end
  end
end

---------------------------------------------------------------- CC API mocks
local function mt(fn) return function(...) os.pullEvent("mock_tick"); return fn(...) end end

local function contact(s)
  local x, y, z = shipPos(s)
  local dx, dy, dz = x - RADAR.x, y - RADAR.y, z - RADAR.z
  return { kind = "sublevel", id = s.id, name = s.name, x = x, y = y, z = z, vx = s.v[1], vy = s.v[2], vz = s.v[3],
    bounds = shipBounds(s), size = (function(b) return { x = b.maxX - b.minX, y = b.maxY - b.minY, z = b.maxZ - b.minZ } end)(shipBounds(s)), distance = math.sqrt(dx * dx + dy * dy + dz * dz),
    iff = (IFF and s.name == "Friend") and { code = "BLUE", callsign = "Friend1" } or nil }
end

local radar = {
  scanSubLevels = mt(function(range)
    local out = {}
    for _, s in ipairs(ships) do local c = contact(s); if c.distance <= range then out[#out + 1] = c end end
    table.sort(out, function(a, b) return a.distance < b.distance end)
    return out
  end),
  scanEntities = mt(function() return {} end),
  getSelf = mt(function()
    if IFF then return { vx = SELFV[1], vy = 0, vz = SELFV[3], iff = { code = "BLUE", callsign = "Home" } } end
    if SELFV[1] == 0 and SELFV[3] == 0 then return nil end
    return { vx = SELFV[1], vy = 0, vz = SELFV[3] }
  end),
  getPosition = mt(function() return { x = RADAR.x, y = RADAR.y, z = RADAR.z } end),
}

local function laserApi(L)
  return {
    aimAt = mt(function(x, y, z)
      local v = { x - L.c[1], y - L.c[2], z - L.c[3] }
      local lx = v[1] * L.right[1] + v[2] * L.right[2] + v[3] * L.right[3]
      local ly = v[1] * L.up[1] + v[2] * L.up[2] + v[3] * L.up[3]
      local lz = v[1] * L.fwd[1] + v[2] * L.fwd[2] + v[3] * L.fwd[3]
      local yaw = math.deg(math.atan(lx, lz))
      local pitch = math.deg(math.atan(ly, math.sqrt(lx * lx + lz * lz)))
      L.ty, L.tp = yaw, clamp(pitch, 90)
      return true, L.ty, L.tp
    end),
    getDirection = mt(function() local d = dirOf(L, L.yaw, L.pitch); local m = muzzle(L, d); return { x = d[1], y = d[2], z = d[3], origin = { x = m[1], y = m[2], z = m[3] } } end),
    getRange = function() return RANGE end,
    isAimed = function() return L.yaw == L.ty and L.pitch == L.tp end,
    trace = mt(function() local d = dirOf(L, L.yaw, L.pitch); return public(trace(muzzle(L, d), d)) end),
    getHit = mt(function() return L.firing and public(L.hit) or nil end),
    fire = mt(function() L.fire = -1; if L.over then return false, "overheated" end return true end),
    stop = mt(function() L.fire = 0 end),
    getHeat = function() return L.heat end,
    isOverheated = function() return L.over end,
    isFiring = function() return L.firing end,
  }
end

local devices = { radar_0 = { type = "radar", api = radar } }
for _, L in ipairs(lasers) do devices[L.name] = { type = "laser", api = laserApi(L) } end
W.lasers = lasers

peripheral = {
  getNames = function() local n = {}; for k in pairs(devices) do n[#n + 1] = k end; table.sort(n); return n end,
  hasType = function(name, t) return devices[name] and devices[name].type == t end,
  wrap = function(name) return devices[name] and devices[name].api end,
  find = function(t) for _, k in ipairs(peripheral.getNames()) do if devices[k].type == t then return devices[k].api end end end,
  getName = function(api) for k, d in pairs(devices) do if d.api == api then return k end end end,
}

function os.pullEventRaw(filter) return coroutine.yield(filter) end
function os.pullEvent(filter)
  local ev = table.pack(os.pullEventRaw(filter))
  if ev[1] == "terminate" then error("Terminated", 0) end
  return table.unpack(ev, 1, ev.n)
end
os.clock = function() return W.tick / 20 end
os.time = function() return 6 + W.tick / 1000 end
textutils = { formatTime = function(t) return ("%.2f"):format(t) end }
sleep = function(s) for _ = 1, math.max(1, math.floor(s * 20)) do os.pullEvent("mock_tick") end end

local function runUntilLimit(routines, limit)
  local count, living, filters, ev = #routines, #routines, {}, { n = 0 }
  while true do
    for n = 1, count do
      local r = routines[n]
      if r and (filters[r] == nil or filters[r] == ev[1] or ev[1] == "terminate") then
        local ok, param = coroutine.resume(r, table.unpack(ev, 1, ev.n))
        if not ok then error(param, 0) end
        filters[r] = param
        if coroutine.status(r) == "dead" then routines[n] = nil; living = living - 1; if living <= limit then return n end end
      end
    end
    ev = table.pack(os.pullEventRaw())
  end
end
local function create(...)
  local fns, out = table.pack(...), {}
  for i = 1, fns.n do out[i] = coroutine.create(fns[i]) end
  return out
end
parallel = {
  waitForAny = function(...) local r = create(...); return runUntilLimit(r, #r - 1) end,
  waitForAll = function(...) local r = create(...); return runUntilLimit(r, 0) end,
}

colors = setmetatable({}, { __index = function() return 1 end })
keys = { space = 57, f = 33, c = 46, q = 16 }
local function screen()
  local o = {}
  for _, k in ipairs({ "setCursorPos", "setTextColor", "setBackgroundColor", "clear", "write", "setVisible", "setTextScale" }) do o[k] = function() end end
  o.getSize = function() return 51, 19 end
  o.write = function(t) if type(t) ~= "string" and type(t) ~= "number" then error("bad argument to write", 2) end end
  return o
end
term = screen()
local current = screen()
term.current = function() return current end
-- Like CC: the term API table itself is refused as a window parent
window = { create = function(parent)
  if parent == term then error("term is not a recommended window parent, try term.current() instead", 2) end
  W.windows = (W.windows or 0) + 1
  return screen()
end }
print = function() end

FILES = IFF and {} or { ["aa_friends.txt"] = "Friend\n" }
fs = {
  exists = function(n) return FILES[n] ~= nil end,
  getSize = function(n) return #(FILES[n] or "") end,
  delete = function(n) FILES[n] = nil end,
  open = function(n, mode)
    if mode == "r" then
      local data = FILES[n]
      if not data then return nil end
      local lines = {}
      for l in data:gmatch("([^\n]*)\n") do lines[#lines + 1] = l end
      local i = 0
      return { readLine = function() i = i + 1; return lines[i] end, close = function() end }
    end
    if mode == "w" then FILES[n] = "" end
    FILES[n] = FILES[n] or ""
    return { writeLine = function(s) FILES[n] = FILES[n] .. s .. "\n" end, close = function() end }
  end,
}
return W
