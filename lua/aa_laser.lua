-- Anti-air laser battery: an AshCC radar finds air targets, every connected laser engages them.
--   aa_laser [range] [--hold] [--spread]
--     range    search range in blocks (default CFG.range)
--     --hold   start with weapons on hold (space to arm)
--     --spread lasers take different targets instead of all on the top threat
-- Keys: space arm/hold, f mark the top target friendly, c clear friends, q quit (monitor touch = arm/hold)
-- Works on a ship too: the radar skips its own ship and the lasers re-aim every cycle as the ship moves.
-- The beam is only switched on while a trace shows it would hit the target, so it never burns
-- the ground, a friendly ship or its own ship that happens to be in the way.
local args = { ... }

local CFG = {
  range = 1024,
  minAltitude = 4,        -- bottom of the target must be this far above the radar (blocks)
  mobs = { "minecraft:phantom", "minecraft:ghast", "minecraft:blaze" }, -- flying mobs to engage ({} = ships only)
  lead = 0.05,            -- seconds of lead for the aim to take effect (on top of the time since the scan)
  threatLookahead = 3,    -- seconds: approaching targets count as this much closer per block/s of closing speed
  spread = false,
  heatHigh = 0.9,         -- burst control: stop at this heat, resume below heatLow
  heatLow = 0.5,          -- (avoids the forced full cool-down after overheating)
  missTicks = 4,          -- ticks aimed with the beam off the target -> next aim point
  settleTicks = 2,        -- ticks for a new aim point to show in the beam (aim and hit are read in the same tick)
  randomPoints = 12,      -- random points inside the target's box tried after the fixed aim points
  giveUp = 6,             -- a target being hit: no hit from any aim point for this long -> skip it
  skipTime = 15,          -- seconds a laser skips a target it could not hit (doubles each time, up to 60)
  maxTries = 3,           -- targets tried per laser per step when picking a new one
  friendsFile = "aa_friends.txt",
  friendCodes = {},       -- IFF codes always treated as friendly, e.g. { BLUE = true }
  trustOwnCode = true,    -- ships sending the same IFF code as our own ship are friendly
  logFile = "aa_laser.log",
  logLimit = 64 * 1024,
}

local S = {
  armed = true,
  running = true,
  friends = {},           -- id or name -> true
  burnt = {},             -- target id -> blocks burnt
  totalBurnt = 0,
  targets = {},           -- ranked
  byId = {},
  scanTime = 0,
  lasers = {},
  self = nil,
  radarPos = nil,
  cycle = 0,
}

for _, a in ipairs(args) do
  if a == "--hold" then S.armed = false
  elseif a == "--spread" then CFG.spread = true
  elseif tonumber(a) then CFG.range = tonumber(a) end
end

---------------------------------------------------------------- log / friends
local function log(fmt, ...)
  local line = ("[%s] " .. fmt):format(textutils.formatTime(os.time(), true), ...)
  local ok = pcall(function()
    if fs.exists(CFG.logFile) and fs.getSize(CFG.logFile) > CFG.logLimit then fs.delete(CFG.logFile) end
    local f = fs.open(CFG.logFile, "a")
    f.writeLine(line)
    f.close()
  end)
  return ok
end

local function loadFriends()
  S.friends = {}
  if not fs.exists(CFG.friendsFile) then return end
  local f = fs.open(CFG.friendsFile, "r")
  for line in f.readLine do
    line = line:gsub("^%s+", ""):gsub("%s+$", "")
    if line ~= "" and line:sub(1, 1) ~= "#" then S.friends[line] = true end
  end
  f.close()
end

local function addFriend(key)
  S.friends[key] = true
  local f = fs.open(CFG.friendsFile, "a")
  f.writeLine(key)
  f.close()
end

-- IFF: ships whose transponder sends our own ship's code (or one of CFG.friendCodes) are friends.
-- Codes can be faked, so a marked id/name in the friends file is the only sure way.
local function isFriend(t)
  if S.friends[t.id] or (t.name and S.friends[t.name]) then return true end
  -- Our own chaff (from a decoy_launcher) shows up with exactly our ship's mass and size
  local own = S.self
  if own and own.mass and t.mass == own.mass and t.size and own.size and t.size.x == own.size.x
    and t.size.y == own.size.y and t.size.z == own.size.z then return true end
  local code = t.iff and t.iff.code
  if not code then return false end
  if CFG.friendCodes[code] then return true end
  return CFG.trustOwnCode and S.self ~= nil and S.self.iff ~= nil and S.self.iff.code == code
end

---------------------------------------------------------------- peripherals
local radar = peripheral.find("radar")
if not radar then error("No radar found", 0) end
for _, name in ipairs(peripheral.getNames()) do
  if peripheral.hasType(name, "laser") then
    local p = peripheral.wrap(name)
    S.lasers[#S.lasers + 1] = { name = name, p = p, reach = p.getRange(), status = "IDLE", aimIdx = 1, miss = 0, settle = 0, skip = {}, skipCount = {} }
  end
end
if #S.lasers == 0 then error("No laser found", 0) end
table.sort(S.lasers, function(a, b) return a.name < b.name end)
local monitor = peripheral.find("monitor")
if monitor then monitor.setTextScale(0.5) end

---------------------------------------------------------------- targets
local function label(t)
  local callsign = t.iff and t.iff.callsign ~= "" and t.iff.callsign
  return t.name or callsign or (t.type and t.type:gsub("^minecraft:", "")) or t.id:sub(1, 8)
end

-- Aim points relative to the target's position: centre of mass, bounding box centre, then the outermost
-- block layer on each side (hits hollow ships and rings whose centre is empty)
local function aimPoints(t)
  local pts = { { 0, 0, 0 } }
  local b = t.bounds
  if b then
    local cx, cy, cz = (b.minX + b.maxX) / 2 - t.x, (b.minY + b.maxY) / 2 - t.y, (b.minZ + b.maxZ) / 2 - t.z
    local ex = math.max(0, (b.maxX - b.minX) / 2 - 0.5)
    local ey = math.max(0, (b.maxY - b.minY) / 2 - 0.5)
    local ez = math.max(0, (b.maxZ - b.minZ) / 2 - 0.5)
    if cx * cx + cy * cy + cz * cz > 0.25 then pts[#pts + 1] = { cx, cy, cz } end
    local seen = {}
    local function add(x, y, z)
      local k = ("%.2f,%.2f,%.2f"):format(x, y, z)
      if (x ~= 0 or y ~= 0 or z ~= 0) and not seen[k] then
        seen[k] = true
        pts[#pts + 1] = { cx + x, cy + y, cz + z }
      end
    end
    -- faces (bottom first: we usually look up at it), then the horizontal corners
    for _, o in ipairs({ { 0, -ey, 0 }, { ex, 0, 0 }, { -ex, 0, 0 }, { 0, 0, ez }, { 0, 0, -ez }, { 0, ey, 0 } }) do add(o[1], o[2], o[3]) end
    for _, sx in ipairs({ -1, 1 }) do for _, sz in ipairs({ -1, 1 }) do add(sx * ex, 0, sz * ez) end end
  end
  return pts
end

-- A random point inside the target's bounding box (relative to its position), to find what is left of it
local function randomPoint(t)
  local b = t.bounds
  if not b then return { 0, 0, 0 } end
  local function r(lo, hi) return lo + 0.5 + math.random() * math.max(0, hi - lo - 1) end
  return { r(b.minX, b.maxX) - t.x, r(b.minY, b.maxY) - t.y, r(b.minZ, b.maxZ) - t.z }
end

-- Radar pass: all main-thread calls in the same tick
local function scan()
  local ships, mobs, self, pos
  local tasks = {
    function() ships = radar.scanSubLevels(CFG.range) end,
    function() self = radar.getSelf() end,
    function() pos = radar.getPosition() end,
  }
  if #CFG.mobs > 0 then
    tasks[#tasks + 1] = function() mobs = radar.scanEntities(CFG.range, { type = CFG.mobs }) end
  end
  parallel.waitForAll(table.unpack(tasks))
  S.self, S.radarPos = self, pos

  local svx, svy, svz = 0, 0, 0
  if self then svx, svy, svz = self.vx, self.vy, self.vz end
  local list = {}
  local function consider(t)
    local bottom = t.bounds and t.bounds.minY or t.y
    if isFriend(t) or bottom - pos.y < CFG.minAltitude then return end
    t.rvx, t.rvy, t.rvz = t.vx - svx, t.vy - svy, t.vz - svz
    local dx, dy, dz = t.x - pos.x, t.y - pos.y, t.z - pos.z
    local d = math.sqrt(dx * dx + dy * dy + dz * dz)
    t.range = d
    t.closing = d > 1e-6 and -(dx * t.rvx + dy * t.rvy + dz * t.rvz) / d or 0
    t.speed = math.sqrt(t.rvx ^ 2 + t.rvy ^ 2 + t.rvz ^ 2)
    t.threat = d - CFG.threatLookahead * math.max(0, t.closing)
    t.points = aimPoints(t)
    list[#list + 1] = t
  end
  for _, t in ipairs(ships or {}) do consider(t) end
  for _, t in ipairs(mobs or {}) do consider(t) end
  table.sort(list, function(a, b) return a.threat < b.threat end)
  S.targets = list
  local byId = {}
  for _, t in ipairs(list) do byId[t.id] = t end
  return byId
end

---------------------------------------------------------------- lasers
-- Where to aim now: the target's world position extrapolated from the last scan (world velocity), plus the
-- lead for the aim to take effect, which uses the velocity relative to our own ship (the laser moves with it)
local function predicted(t, o)
  local age, lead = os.clock() - S.scanTime, CFG.lead
  return t.x + o[1] + t.vx * age + t.rvx * lead, t.y + o[2] + t.vy * age + t.rvy * lead, t.z + o[3] + t.vz * age + t.rvz * lead
end

-- While the beam is on the target it sticks to the point it hits, so it keeps burning the same block
-- Aim points past the fixed ones are random points inside the bounds
local function aim(L, t, idx)
  local o = L.stick and L.target == t.id and L.stick
  if not o and idx > #t.points then
    if not L.rand or L.randIdx ~= idx then L.rand, L.randIdx = randomPoint(t), idx end
    o = L.rand
  end
  return L.p.aimAt(predicted(t, o or t.points[idx] or t.points[1]))
end

local function onTarget(hit, t)
  if not hit or hit.type == "none" then return false end
  if hit.entity and hit.entity.id == t.id then return true end
  return t.kind == "sublevel" and hit.subLevel == t.id
end

local function cease(L)
  if L.firing then L.p.stop() end
  L.firing = false
end

-- Re-aim and look where the beam ends in the same tick (both are main-thread calls)
local function aimAndLook(L, t)
  local ok, hit, dir
  parallel.waitForAll(
    function() ok = aim(L, t, L.aimIdx) end,
    function() dir = L.p.getDirection() end,
    function()
      if L.firing then hit = L.p.getHit() end
      if not hit then hit = L.p.trace() end
    end)
  return ok, hit, dir
end

-- Within the beam's length (the radar may see much farther)
local function inReach(L, t)
  -- From where the laser is now: its offset from the radar (measured while it was aiming) plus the latest scan
  local o = S.radarPos
  if not o then return true end
  local off = L.offset or { 0, 0, 0 }
  local dx, dy, dz = t.x - o.x - off[1], t.y - o.y - off[2], t.z - o.z - off[3]
  local half = t.size and math.max(t.size.x, t.size.y, t.size.z) / 2 or 1
  return math.sqrt(dx * dx + dy * dy + dz * dz) - half <= L.reach
end

local function pickTarget(L, i, now)
  local order, n = {}, #S.targets
  local first = CFG.spread and ((i - 1) % math.max(n, 1)) + 1 or 1
  for k = 0, n - 1 do order[#order + 1] = S.targets[((first - 1 + k) % n) + 1] end
  local tries = 0
  for _, cand in ipairs(order) do
    if tries >= CFG.maxTries then break end
    if not (L.skip[cand.id] and L.skip[cand.id] > now) and inReach(L, cand) then
      tries = tries + 1
      if aim(L, cand, ((i - 1) % #cand.points) + 1) then
        -- Lasers on the same target start on different aim points: each laser burns its own block
        L.target, L.aimIdx, L.miss, L.lastOn, L.stick, L.settle = cand.id, ((i - 1) % #cand.points) + 1, 0, now, nil, CFG.settleTicks
        L.everHit, L.tried = false, 0
        log("%s engages %s (%s) at %.0f m", L.name, label(cand), cand.id:sub(1, 8), cand.range)
        return cand
      end
    end
  end
end

-- One step of one laser, about one tick
local function engage(L, i)
  local now = os.clock()
  -- Burst control keeps the laser out of the overheat lock-out
  local heat = L.p.getHeat()
  L.heat = heat
  if L.p.isOverheated() or heat >= CFG.heatHigh then L.cooling = true
  elseif heat <= CFG.heatLow then L.cooling = false end

  -- Keep the locked target while it is around; otherwise pick by threat
  local t = L.target and S.byId[L.target]
  if t and not inReach(L, t) then t = nil end
  local ok, hit, dir
  if t then
    ok, hit, dir = aimAndLook(L, t)
    -- The laser's offset from the radar; its world origin goes stale as soon as the ship moves on
    if dir and dir.origin and S.radarPos then
      L.offset = { dir.origin.x - S.radarPos.x, dir.origin.y - S.radarPos.y, dir.origin.z - S.radarPos.z }
    end
    if not ok then t = nil end
  end
  if not t then
    L.target = nil
    t = #S.targets > 0 and pickTarget(L, i, now)
    if not t then
      cease(L)
      L.status = #S.targets > 0 and "WAIT" or "IDLE" -- WAIT: targets out of reach or skipped (blocked)
      L.label = nil
      sleep(0.05)
      return
    end
    L.label = label(t)
    cease(L)
    L.status = "TRACK"
    return
  end
  L.label = label(t)

  -- Fire only while the beam really ends on the target
  local on = onTarget(hit, t)
  if on then L.lastOn, L.everHit = now, true end
  if L.settle > 0 then
    -- The hit we see is from the previous tick's aim: wait for a new aim point to take effect
    L.settle = L.settle - 1
  elseif on then
    L.miss = 0
    -- Fix the point once (relative to the target); re-sticking every tick would let timing errors add up
    if not L.stick then
      local px, py, pz = predicted(t, { 0, 0, 0 })
      local lead = CFG.lead
      L.stick = { hit.x + dir.x * 0.3 - px + t.rvx * lead, hit.y + dir.y * 0.3 - py + t.rvy * lead, hit.z + dir.z * 0.3 - pz + t.rvz * lead }
      L.settle = CFG.settleTicks
    end
  elseif L.stick then
    -- Burnt through or slipped off: straight on to the next aim point
    L.stick, L.miss = nil, 0
    L.aimIdx = L.aimIdx % (#t.points + CFG.randomPoints) + 1
    L.settle = CFG.settleTicks
  elseif L.p.isAimed() then
    L.miss = L.miss + 1
    if L.miss >= CFG.missTicks then
      L.miss = 0
      L.tried = L.tried + 1
      L.aimIdx = L.aimIdx % (#t.points + CFG.randomPoints) + 1
      L.settle = CFG.settleTicks
      -- Never hit after every fixed aim point: it is behind something. Once hit, keep searching (random points
      -- find what is left of it) until nothing has been hit for giveUp seconds.
      if (not L.everHit and L.tried >= #t.points) or (L.everHit and now - L.lastOn > CFG.giveUp) then
        local n = (L.skipCount[t.id] or 0) + 1
        L.skipCount[t.id] = n
        local wait = math.min(60, CFG.skipTime * 2 ^ (n - 1))
        log("%s cannot hit %s (blocked), skipping it for %d s", L.name, label(t), wait)
        L.skip[t.id] = now + wait
        L.target = nil
      end
    end
  end

  if S.armed and on and not L.cooling then
    if not L.firing then
      L.firing = L.p.fire() == true
      -- HOLD may have been pressed while fire() was in flight
      if not S.armed then cease(L) end
    end
    L.status = S.armed and "FIRE" or "HOLD"
  else
    cease(L)
    if not S.armed then L.status = "HOLD"
    elseif L.cooling then L.status = "COOL"
    elseif hit and hit.type ~= "none" then L.status = "BLOCKED"
    else L.status = "TRACK" end
  end
end

---------------------------------------------------------------- display
-- One hidden buffer window per screen (CC only accepts term.current(), not the term table, as a parent)
local screen = term.current()
local buffers = {}
local function draw(dev)
  local w, h = dev.getSize()
  local win = buffers[dev]
  if not win or win.w ~= w or win.h ~= h then
    win = { win = window.create(dev, 1, 1, w, h, false), w = w, h = h }
    buffers[dev] = win
  end
  win = win.win
  local function line(y, text, fg, bg)
    win.setCursorPos(1, y)
    win.setBackgroundColor(bg or colors.black)
    win.setTextColor(fg or colors.white)
    win.write(text:sub(1, w) .. (" "):rep(math.max(0, w - #text)))
  end
  win.setBackgroundColor(colors.black)
  win.clear()
  line(1, (" AA LASER  %s  range %d  burnt %d"):format(S.armed and "ARMED" or "HOLD", CFG.range, S.totalBurnt),
    colors.white, S.armed and colors.red or colors.gray)
  local y = 2
  for _, L in ipairs(S.lasers) do
    local col = ({ FIRE = colors.red, COOL = colors.lightBlue, BLOCKED = colors.orange, TRACK = colors.yellow })[L.status] or colors.lightGray
    line(y, ("%-10s %-7s %3d%% %s"):format(L.name, L.status, math.floor((L.heat or 0) * 100), L.label or ""), col)
    y = y + 1
  end
  line(y, (" target           dist  alt  spd  burnt"), colors.lightGray, colors.gray)
  y = y + 1
  for _, t in ipairs(S.targets) do
    if y > h - 1 then break end
    local engaged = false
    for _, L in ipairs(S.lasers) do if L.target == t.id then engaged = true end end
    local alt = S.radarPos and (t.y - S.radarPos.y) or 0
    line(y, ("%s%-15s %5.0f %+4.0f %4.0f %5d"):format(engaged and ">" or " ", label(t):sub(1, 15), t.range, alt, t.speed,
      S.burnt[t.id] or 0), engaged and colors.red or colors.white)
    y = y + 1
  end
  if #S.targets == 0 then line(y, " no air targets", colors.lightGray) end
  line(h, " space arm/hold  f friend  c clear  q quit", colors.lightGray, colors.gray)
  win.setVisible(true)
  win.setVisible(false)
end

---------------------------------------------------------------- loops
local function setArmed(a)
  S.armed = a
  log(a and "ARMED" or "HOLD")
  if not a then
    -- All lasers off in the same tick
    local jobs = {}
    for _, L in ipairs(S.lasers) do jobs[#jobs + 1] = function() L.firing = false; L.p.stop() end end
    parallel.waitForAll(table.unpack(jobs))
  end
end

local function radarLoop()
  while S.running do
    local byId = scan()
    S.byId, S.scanTime = byId, os.clock()
    for _, L in ipairs(S.lasers) do
      if L.target and not byId[L.target] then
        log("%s lost target %s (burnt %d)", L.name, L.target:sub(1, 8), S.burnt[L.target] or 0)
        L.target = nil
      end
    end
    S.cycle = S.cycle + 1
    draw(screen)
    if monitor then draw(monitor) end
  end
end

-- The radar and every laser run their own loops, so each laser re-aims every tick
local function combat()
  local jobs = { radarLoop }
  for i, L in ipairs(S.lasers) do
    jobs[#jobs + 1] = function() while S.running do engage(L, i) end end
  end
  parallel.waitForAll(table.unpack(jobs))
end

local function events()
  while S.running do
    local ev = { os.pullEvent() }
    if ev[1] == "laser_break" then
      S.totalBurnt = S.totalBurnt + 1
      local sub = ev[7]
      if sub then S.burnt[sub] = (S.burnt[sub] or 0) + 1 end
    elseif ev[1] == "laser_overheat" then
      log("%s overheated", ev[2])
    elseif ev[1] == "key" then
      local k = ev[2]
      if k == keys.space then setArmed(not S.armed)
      elseif k == keys.f and S.targets[1] then
        local t = S.targets[1]
        addFriend(t.id)
        log("marked %s (%s) friendly", label(t), t.id)
      elseif k == keys.c then
        if fs.exists(CFG.friendsFile) then fs.delete(CFG.friendsFile) end
        S.friends = {}
        log("friends cleared")
      elseif k == keys.q then
        S.running = false
      end
    elseif ev[1] == "monitor_touch" then
      setArmed(not S.armed)
    end
  end
end

loadFriends()
log("start: radar %s, %d lasers, range %d, %s", peripheral.getName(radar), #S.lasers, CFG.range, S.armed and "armed" or "hold")
local ok, err = pcall(parallel.waitForAny, combat, events)
for _, L in ipairs(S.lasers) do pcall(L.p.stop) end
term.setBackgroundColor(colors.black)
term.setTextColor(colors.white)
term.clear()
term.setCursorPos(1, 1)
log("stop%s", ok and "" or (": " .. tostring(err)))
if not ok and err ~= "Terminated" then error(err, 0) end
print("AA laser stopped, lasers off.")
