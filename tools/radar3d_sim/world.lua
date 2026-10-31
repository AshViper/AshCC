-- Mock CC + radar + HD monitor for lua/radar_3d.lua (run by run.js under fengari).
-- The radar sits at y 80.5 facing east over hills, a mountain and a lake; draw commands are recorded per frame.
local S = { time = 0, queue = {}, timers = {}, nextTimer = 1, frames = {}, cmds = {} }

local function terrainY(x, z)
  local h = 64 + 30 * math.exp(-((x - 80) ^ 2 + (z + 60) ^ 2) / 1500)
    + 70 * math.exp(-((x + 120) ^ 2 + (z - 100) ^ 2) / 3000)
    - 18 * math.exp(-((x - 60) ^ 2 + (z - 90) ^ 2) / 1200)
    + 6 * math.sin(x / 25) * math.cos(z / 30)
  return math.floor(h)
end

local radarPos = { x = 0.5, y = 80.5, z = 0.5 }
local fwd, right = { x = 1, y = 0, z = 0 }, { x = 0, y = 0, z = 1 } -- facing east

local function scanTerrain(range, size, northUp)
  range, size = range or 2048, size or 32
  local f, r = fwd, right
  if northUp then f, r = { x = 0, y = 0, z = -1 }, { x = 1, y = 0, z = 0 } end
  local cell = 2 * range / size
  local t = { size = size, range = range, cell = cell, northUp = northUp or false, originX = radarPos.x, originY = radarPos.y,
    originZ = radarPos.z, forward = f, right = r, height = {}, floor = {}, color = {}, kind = {} }
  local mn, mx = math.huge, -math.huge
  for row = 1, size do
    local lz = range - (row - 0.5) * cell
    t.height[row], t.floor[row], t.color[row], t.kind[row] = {}, {}, {}, {}
    for col = 1, size do
      local lx = -range + (col - 0.5) * cell
      local x = math.floor(radarPos.x + r.x * lx + f.x * lz)
      local z = math.floor(radarPos.z + r.z * lx + f.z * lz)
      local g = terrainY(x, z)
      local h, kind, c = g, "ground", 0x7FB238
      if g < 62 then h, kind, c = 62, "water", 0x4040FF
      elseif g > 118 then kind, c = "snow", 0xFFFFFF
      elseif g > 98 then c = 0x707070 end
      t.height[row][col], t.floor[row][col], t.color[row][col], t.kind[row][col] = h, math.min(g, h), c, kind
      mn, mx = math.min(mn, h), math.max(mx, h)
    end
  end
  t.min, t.max = mn, mx
  return t
end

local function ship(id, name, x, y, z, code)
  local dx, dy, dz = x - radarPos.x, y - radarPos.y, z - radarPos.z
  return { kind = "sublevel", id = id, name = name, x = x, y = y, z = z, distance = math.sqrt(dx * dx + dy * dy + dz * dz),
    iff = code and { code = code, callsign = name } or nil }
end
local function player(name, x, y, z)
  local c = ship(name .. "-uuid", name, x, y, z)
  c.kind, c.isPlayer, c.iff = "entity", true, nil
  return c
end

local radar = {
  getRange = function() return 2048 end,
  scanTerrain = function(...) S.terrainScans = (S.terrainScans or 0) + 1; return scanTerrain(...) end,
  scan = function()
    return { subLevels = { ship("a1", "Raider", 150, 140, -90), ship("b2", "Wingman", -60, 110, 40, 7), ship("c3", "Bandit", 40, 75, 200) },
      entities = { player("Steve", 30, 66, -20), { kind = "entity", isPlayer = false, x = 0, y = 0, z = 0 } } }
  end,
  getSelf = function() return { iff = { code = 7 } } end,
  getOrientation = function() return { forward = fwd, right = right, up = { x = 0, y = 1, z = 0 } } end,
}

local W, H = 1024, 640
local mon = setmetatable({ getResolution = function() return W, H end, present = function()
  S.frames[#S.frames + 1] = S.cmds
  S.cmds = {}
end }, { __index = function(_, k)
  return function(...)
    local a = table.pack(...)
    local rec = { k }
    for i = 1, a.n do rec[#rec + 1] = a[i] end
    S.cmds[#S.cmds + 1] = rec
  end
end })

peripheral = { find = function(t) if t == "radar" then return radar elseif t == "hd_monitor" then return mon end end }
keys = { q = 16 }
os.clock = function() return S.time end
os.startTimer = function(s) local id = S.nextTimer; S.nextTimer = id + 1; S.timers[#S.timers + 1] = { at = S.time + s, id = id }; return id end
os.queueEvent = function(...) S.queue[#S.queue + 1] = table.pack(...) end
os.pullEventRaw = function(filter) return coroutine.yield(filter) end
os.pullEvent = os.pullEventRaw
sleep = function(s)
  local id = os.startTimer(s)
  repeat local _, t = os.pullEvent("timer") until t == id
end
parallel = { waitForAny = function(...)
  local cos, filters = {}, {}
  for i, f in ipairs({ ... }) do cos[i] = coroutine.create(f) end
  local ev = { n = 0 }
  while true do
    for i, co in ipairs(cos) do
      if filters[i] == nil or filters[i] == ev[1] then
        local ok, f = coroutine.resume(co, table.unpack(ev, 1, ev.n))
        if not ok then error(f, 0) end
        if coroutine.status(co) == "dead" then return end
        filters[i] = f
      end
    end
    ev = table.pack(coroutine.yield())
  end
end }

-- Drives the program: delivers queued events, then the earliest timer; scripted input at given times.
function S.run(prog, script)
  local main = coroutine.create(prog)
  local function resume(...)
    local ok, e = coroutine.resume(main, ...)
    if not ok then error("program error at t=" .. S.time .. ": " .. tostring(e), 0) end
  end
  resume()
  local si = 1
  while coroutine.status(main) ~= "dead" and S.time < 30 do
    if #S.queue > 0 then
      local ev = table.remove(S.queue, 1)
      resume(table.unpack(ev, 1, ev.n))
    else
      table.sort(S.timers, function(a, b) return a.at < b.at end)
      local nt = S.timers[1]
      local sc = script[si]
      if sc and (not nt or sc[1] <= nt.at) then
        S.time = math.max(S.time, sc[1])
        for i = 2, #sc do S.queue[#S.queue + 1] = sc[i] end
        si = si + 1
      elseif nt then
        table.remove(S.timers, 1)
        S.time = math.max(S.time, nt.at)
        resume("timer", nt.id)
      else break end
    end
  end
  return coroutine.status(main)
end

return S
