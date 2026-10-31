-- UAV: an AshCC anti-gravity core flies the ship, the radar finds and identifies contacts, every laser attacks hostiles.
--   uav [range] [--hold] [--home]
--     range    radar range in blocks (default CFG.range)
--     --hold   start with weapons on hold (space to arm)
--     --home   make the current position the new home (centre of the patrol circle)
-- Keys: space arm/hold, h circle here / resume, r return home, f mark the top contact friendly,
--       e mark it hostile, c clear the marks, l learning on/off, x forget what was learned (back to the built-in
--       brain), q quit (the ship hovers in place, lasers off). Monitor touch = arm/hold.
-- On the ship: an anti-gravity core, a radar, lasers and this computer (directly or through a link hub).
-- The ship is built nose north: the core's yaw 0 is the way the ship faced when it was assembled.
-- Identification by radar:
--   FRIEND   marked friendly, IFF code of our own ship or in CFG.friendCodes, our own chaff
--   HOSTILE  marked hostile, IFF code in CFG.hostileCodes, a mob in CFG.mobs, or (CFG.unknown = "hostile")
--            any other ship faster than CFG.minTargetSpeed (a ship once seen hostile stays hostile)
--   DEAD     any ship but a friend that has not moved for CFG.deadTime seconds (falling straight down counts as
--            not moving): shot down or unmanned, never attacked; it comes back if it moves again
--   UNKNOWN  everything else: shown, never attacked
-- Flight, like a fighter: the program flies a simple aircraft (speed, heading, climb angle) and sends its velocity
-- to the core, so the ship always moves the way its nose points. It turns by banking (turn rate = g tan(bank) / speed,
-- rolling in and out smoothly), climbs and dives with the nose, and never flies slower than CFG.minSpeed (from the
-- ground it accelerates straight ahead first, like a take-off run).
-- AI pilot: every CFG.decideEvery s a neural network (pre-trained in tools/uav_ai, built in at the end of this file)
-- looks at our state, the terrain ahead (radar.scanTerrain) and the two most threatening hostiles, and picks a bank,
-- climb, speed and which hostile to focus on. With CFG.learn it keeps learning from what happens (damage dealt and
-- taken, kills, terrain warnings) and saves itself to CFG.brainFile. A safety layer always overrides it: terrain
-- ahead -> wings level and climb (or turn to the lower side), far past the fence -> turn home.
-- h and r fly by hand-written rules (circle here / go home); so does the program when there is no brain.
-- Lasers fire inside CFG.gunCone around the nose (0 = all around).
-- The beam is only switched on while a trace shows it would hit the target, so it never burns the ground,
-- a friend or our own ship that happens to be in the way.
local args = { ... }

local CFG = {
  range = 1024,
  -- identification
  mobs = { "minecraft:phantom", "minecraft:ghast" }, -- hostile flying mobs, lasers only ({} = ships only)
  friendCodes = {},       -- IFF codes always friendly, e.g. { BLUE = true }
  hostileCodes = {},      -- IFF codes always hostile, e.g. { RED = true }
  trustOwnCode = true,    -- ships sending the same IFF code as our own ship are friendly
  unknown = "hostile",    -- other ships: "hostile" (attack when moving) or "ignore"
  minTargetSpeed = 1,     -- slower than this (falling straight down does not count) = not moving
  deadTime = 3,           -- seconds not moving -> DEAD: never attacked or chased (moving again revives it)
  -- flight (the aircraft the program flies)
  -- (the AI was trained with these numbers: change them and it flies a different aircraft than it learned)
  maxSpeed = 30,          -- blocks/s
  minSpeed = 12,          -- never slower in the air (stall speed)
  thrust = 8,             -- speed gained per second (blocks/s^2)
  drag = 6,               -- speed lost per second when slowing down
  gravity = 9.8,          -- for turns: turn rate = gravity * tan(bank) / speed (lower = wider turns)
  maxBank = 60,           -- deg
  rollTime = 0.8,         -- seconds to roll from level to full bank
  maxClimb = 30,          -- deg
  maxDive = 35,           -- deg
  pitchRate = 30,         -- deg/s change of the climb angle
  patrolHeight = 30,      -- patrol altitude above home
  patrolRadius = 80,
  engageRange = 400,      -- scale of target distances the AI sees
  fence = 600,            -- the AI is penalised past this, the safety layer turns home past 1.3x
  gunCone = 0,            -- lasers fire only at targets within this angle of the nose (0 = all around)
  -- terrain and safety layer
  terrainRange = 192,     -- radar.scanTerrain radius (must cover 4 s of flight ahead)
  terrainSize = 96,       -- cells per side (4-block cells)
  terrainEvery = 1,       -- seconds between terrain scans
  gcasTime = 3,           -- seconds of flight path checked for terrain
  gcasMargin = 6,         -- blocks of clearance the safety layer keeps
  -- AI
  decideEvery = 0.5,      -- seconds between decisions
  brainFile = "uav_brain.dat",
  learn = true,           -- keep learning in the game (l toggles)
  explore = 0.03,         -- while learning: chance per decision branch of trying something else
  learnRate = 0.0005,
  learnBatch = 8,         -- samples per update
  learnUpdates = 2,       -- updates per decision
  learnStart = 200,       -- decisions collected before learning starts
  replaySize = 2000,      -- decisions remembered for learning
  gamma = 0.97,           -- as in training
  rewardScale = 0.1,
  targetEvery = 200,      -- updates between target-network copies
  saveEvery = 120,        -- seconds between brain saves while learning
  -- core limits (the core must follow the aircraft's turns: speed^2 / radius stays below gravity * tan(maxBank))
  acceleration = 40,      -- blocks/s^2
  turnRate = 120,         -- deg/s
  -- lasers
  lead = 0.05,            -- seconds of lead for the aim to take effect (on top of the time since the scan)
  threatLookahead = 3,    -- seconds: approaching targets count as this much closer per block/s of closing speed
  heatHigh = 0.9,         -- burst control: stop at this heat, resume below heatLow
  heatLow = 0.5,
  missTicks = 4,          -- ticks aimed with the beam off the target -> next aim point
  settleTicks = 2,        -- ticks for a new aim point to show in the beam
  randomPoints = 12,      -- random points inside the target's box tried after the fixed aim points
  giveUp = 6,             -- a target being hit: no hit from any aim point for this long -> skip it
  skipTime = 15,          -- seconds a laser skips a target it could not hit (doubles each time, up to 60)
  maxTries = 3,           -- targets tried per laser per step when picking a new one
  -- files
  marksFile = "uav_marks.txt",
  homeFile = "uav_home.txt",
  logFile = "uav.log",
  logLimit = 64 * 1024,
}

local S = {
  armed = true,
  running = true,
  newHome = false,
  marks = {},             -- id or name -> "FRIEND" / "HOSTILE"
  seenHostile = {},       -- id -> true: stays hostile while it slows down or turns its transponder off
  lastMoved = {},         -- id -> os.clock() when it last moved (or was first seen)
  dead = {},              -- id -> true while it counts as dead
  contacts = {},          -- everything the radar sees, with .class
  targets = {},           -- hostiles, ranked
  byId = {},
  scanTime = 0,
  lasers = {},
  self = nil,
  radarPos = nil,
  home = nil,
  mode = "START",
  hover = nil,            -- centre of the circle flown after h
  rtb = false,
  chase = nil,            -- id of the hostile the AI focuses on (the lasers take it first)
  air = nil,              -- the aircraft: {speed, yaw, climb (deg), turn (deg/s), time}
  att = nil,              -- last attitude sent {yaw, pitch, roll}
  terrain = nil,          -- last radar.scanTerrain grid {t, x, z}
  override = nil,         -- what the safety layer is doing ("terrain" / "fence")
  -- AI
  brain = nil, brainFrom = nil, target = nil,
  learning = false,
  cmd = { 0, 0, 23 },     -- bank, climb, speed from the last decision
  prev = { 0, 0, 1 },     -- bank, climb, speed index (part of the observation)
  nextDecide = 0,
  lastObs = nil, lastAct = nil, q = 0,
  replay = {}, replayNext = 1, updates = 0, lastSave = 0, returnAvg = 0,
  reward = 0,             -- reward collected since the last decision
  flag = {},              -- terrain / fence override since the last decision
  hurt = {},              -- id -> os.clock() we last burnt a block of it
  killed = {},
  lastRange = {},         -- id -> last distance (a hurt hostile that vanishes close by was destroyed)
  maxMass = {},           -- id -> largest mass seen (its health = mass / this)
  mass0 = nil, lastMass = nil,
  burnt = {},
  totalBurnt = 0,
}

-- Reward terms, as in tools/uav_ai/sim.js (per block burnt / per block lost: our health is mass, 30 blocks)
local REWARD = { dealt = 1, kill = 20, taken = 1.5, terrain = 1, fence = 0.5, time = 0.01, ownBlocks = 30 }

for _, a in ipairs(args) do
  if a == "--hold" then S.armed = false
  elseif a == "--home" then S.newHome = true
  elseif tonumber(a) then CFG.range = tonumber(a) end
end

---------------------------------------------------------------- log / files
local function log(fmt, ...)
  local line = ("[%s] " .. fmt):format(textutils.formatTime(os.time(), true), ...)
  pcall(function()
    if fs.exists(CFG.logFile) and fs.getSize(CFG.logFile) > CFG.logLimit then fs.delete(CFG.logFile) end
    local f = fs.open(CFG.logFile, "a")
    f.writeLine(line)
    f.close()
  end)
end

-- Marks file: one "FRIEND <id or name>" / "HOSTILE <id or name>" per line
local function loadMarks()
  S.marks = {}
  if not fs.exists(CFG.marksFile) then return end
  local f = fs.open(CFG.marksFile, "r")
  for line in f.readLine do
    local class, key = line:match("^%s*(%u+)%s+(.-)%s*$")
    if (class == "FRIEND" or class == "HOSTILE") and key ~= "" then S.marks[key] = class end
  end
  f.close()
end

local function mark(key, class)
  S.marks[key] = class
  if class == "FRIEND" then S.seenHostile[key] = nil end
  local f = fs.open(CFG.marksFile, "a")
  f.writeLine(class .. " " .. key)
  f.close()
end

local function loadHome()
  if S.newHome or not fs.exists(CFG.homeFile) then return nil end
  local f = fs.open(CFG.homeFile, "r")
  local x, y, z = (f.readLine() or ""):match("^(%S+) (%S+) (%S+)$")
  f.close()
  if not tonumber(z) then return nil end
  return { x = tonumber(x), y = tonumber(y), z = tonumber(z) }
end

local function saveHome(h)
  local f = fs.open(CFG.homeFile, "w")
  f.writeLine(("%.2f %.2f %.2f"):format(h.x, h.y, h.z))
  f.close()
end

---------------------------------------------------------------- peripherals
local core = peripheral.find("antigravity")
if not core then error("No anti-gravity core found", 0) end
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

local reach = math.huge
for _, L in ipairs(S.lasers) do reach = math.min(reach, L.reach) end

---------------------------------------------------------------- identification
local function label(t)
  local callsign = t.iff and t.iff.callsign ~= "" and t.iff.callsign
  return t.name or callsign or (t.type and t.type:gsub("^minecraft:", "")) or t.id:sub(1, 8)
end

local function classify(t)
  local m = S.marks[t.id] or (t.name and S.marks[t.name])
  if m == "FRIEND" then return m end
  -- Our own chaff (from a decoy_launcher) shows up with exactly our ship's mass and size
  local own = S.self
  if own and own.mass and t.mass == own.mass and t.size and own.size and t.size.x == own.size.x
    and t.size.y == own.size.y and t.size.z == own.size.z then return "FRIEND" end
  local code = t.iff and t.iff.code
  if code then
    if CFG.friendCodes[code] then return "FRIEND" end
    if CFG.trustOwnCode and own and own.iff and own.iff.code == code then return "FRIEND" end
  end
  if t.kind ~= "sublevel" then return "HOSTILE" end -- only the mobs in CFG.mobs are scanned
  -- A ship that has not moved for deadTime is dead (shot down, or nobody flying it): left alone, marked or not
  if t.still >= CFG.deadTime then return "DEAD" end
  if m or (code and CFG.hostileCodes[code]) or S.seenHostile[t.id] then return "HOSTILE" end
  if CFG.unknown == "hostile" and t.wspeed >= CFG.minTargetSpeed then return "HOSTILE" end
  return "UNKNOWN"
end

---------------------------------------------------------------- radar
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
    for _, o in ipairs({ { 0, -ey, 0 }, { ex, 0, 0 }, { -ex, 0, 0 }, { 0, 0, ez }, { 0, 0, -ez }, { 0, ey, 0 } }) do add(o[1], o[2], o[3]) end
    for _, sx in ipairs({ -1, 1 }) do for _, sz in ipairs({ -1, 1 }) do add(sx * ex, 0, sz * ez) end end
  end
  return pts
end

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
  local all, hostile = {}, {}
  local now = os.clock()
  local function consider(t)
    t.wspeed = math.sqrt(t.vx ^ 2 + t.vy ^ 2 + t.vz ^ 2)
    -- Moving = flying under its own power: falling straight down (a wreck) does not count
    local own = math.sqrt(t.vx ^ 2 + t.vz ^ 2 + math.max(t.vy, 0) ^ 2)
    if own >= CFG.minTargetSpeed or not S.lastMoved[t.id] then S.lastMoved[t.id] = now end
    t.still = now - S.lastMoved[t.id]
    t.rvx, t.rvy, t.rvz = t.vx - svx, t.vy - svy, t.vz - svz
    local dx, dy, dz = t.x - pos.x, t.y - pos.y, t.z - pos.z
    local d = math.sqrt(dx * dx + dy * dy + dz * dz)
    t.range = d
    t.closing = d > 1e-6 and -(dx * t.rvx + dy * t.rvy + dz * t.rvz) / d or 0
    t.threat = d - CFG.threatLookahead * math.max(0, t.closing)
    t.class = classify(t)
    if t.class == "DEAD" and S.seenHostile[t.id] and not S.dead[t.id] then
      log("%s (%s) is dead: not moved for %.0f s", label(t), t.id:sub(1, 8), t.still)
    elseif t.class ~= "DEAD" and S.dead[t.id] then
      log("%s (%s) is moving again", label(t), t.id:sub(1, 8))
    end
    S.dead[t.id] = t.class == "DEAD" or nil
    all[#all + 1] = t
    if t.class == "HOSTILE" then
      if t.kind == "sublevel" and not S.seenHostile[t.id] then
        S.seenHostile[t.id] = true
        log("hostile %s (%s) at %.0f m%s", label(t), t.id:sub(1, 8), d, t.iff and (" IFF " .. t.iff.code) or "")
      end
      t.points = aimPoints(t)
      hostile[#hostile + 1] = t
    end
  end
  for _, t in ipairs(ships or {}) do consider(t) end
  for _, t in ipairs(mobs or {}) do consider(t) end
  table.sort(all, function(a, b) return a.range < b.range end)
  table.sort(hostile, function(a, b) return a.threat < b.threat end)
  S.contacts, S.targets = all, hostile

  -- Rewards for the AI: kills (a hostile we burnt that is now dead, or vanished close by) and damage taken
  local seen = {}
  for _, t in ipairs(all) do
    seen[t.id] = t
    if t.mass then S.maxMass[t.id] = math.max(S.maxMass[t.id] or 0, t.mass) end
  end
  for id, when in pairs(S.hurt) do
    if not S.killed[id] and now - when < 30 and (S.dead[id] or (not seen[id] and (S.lastRange[id] or 1e9) < 600)) then
      S.killed[id] = true
      S.reward = S.reward + REWARD.kill
      log("destroyed %s", id:sub(1, 8))
    end
  end
  for _, t in ipairs(all) do S.lastRange[t.id] = t.range end
  if self and self.mass then
    S.mass0 = S.mass0 or self.mass
    if S.lastMass and self.mass < S.lastMass then
      S.reward = S.reward - REWARD.taken * (S.lastMass - self.mass) / S.mass0 * REWARD.ownBlocks
    end
    S.lastMass = self.mass
  end
  local byId = {}
  for _, t in ipairs(hostile) do byId[t.id] = t end
  return byId
end

---------------------------------------------------------------- terrain
-- radar.scanTerrain(range, size, true): a north-up height grid around the radar, refreshed every CFG.terrainEvery s.
-- Without it (older radar) or for unloaded columns the ground is taken to be just below home.
local function heightAt(x, z)
  local T = S.terrain
  if T then
    local t = T.t
    local col = math.floor((x - T.x + t.range) / t.cell) + 1
    local row = math.floor((t.range + (z - T.z)) / t.cell) + 1
    if row >= 1 and row <= t.size and col >= 1 and col <= t.size then
      local h = t.height[row] and t.height[row][col]
      if h then return h + 1 end -- top of the top block
    end
  end
  return S.home.y - 2
end

local function terrainLoop()
  if not radar.scanTerrain then
    log("radar has no scanTerrain: terrain is assumed flat at home")
    return
  end
  while S.running do
    local t, pos
    local ok = pcall(parallel.waitForAll,
      function() t = radar.scanTerrain(CFG.terrainRange, CFG.terrainSize, true) end,
      function() pos = radar.getPosition() end)
    -- originX/originZ: where the radar was when the grid was sampled (getPosition may be a tick apart)
    if ok and t and t.height and (t.originX or pos) then S.terrain = { t = t, x = t.originX or pos.x, z = t.originZ or pos.z } end
    sleep(CFG.terrainEvery)
  end
end

-- AI CORE BEGIN: the same code as tools/uav_ai/common.js (aircraft, safety layer, observation); parity.js checks it
local AI = {
  BANKS = { -60, -30, 0, 30, 60 }, CLIMBS = { -20, 0, 20 }, SPEEDS = { 16, 23, 30 }, BRANCHES = { 5, 3, 3, 2 },
  PROBES = { { 0, 0 }, { 1, 0 }, { 2, 0 }, { 4, 0 }, { 2, -35 }, { 4, -35 }, { 2, 35 }, { 4, 35 } },
}
local atan2 = math.atan2 or math.atan
local RAD = math.pi / 180
local function clamp(x, lo, hi) if x < lo then return lo elseif x > hi then return hi end return x end
local function wrap180(a) return (a + 180) % 360 - 180 end
local function yawTo(dx, dz) return atan2(dx, -dz) / RAD end -- north 0, east 90

-- One step of the aircraft (A = {speed, yaw, climb, turn}) under a bank / climb / speed command
function AI.airStep(A, bankCmd, climbCmd, speedCmd, dt)
  A.speed = A.speed + clamp(clamp(speedCmd, CFG.minSpeed, CFG.maxSpeed) - A.speed, -CFG.drag * dt, CFG.thrust * dt)
  local flying = A.speed >= CFG.minSpeed * 0.8
  local v = math.max(A.speed, CFG.minSpeed)
  local full = math.tan(CFG.maxBank * RAD) * CFG.gravity / v / RAD
  local want = 0
  if flying then want = math.tan(clamp(bankCmd, -CFG.maxBank, CFG.maxBank) * RAD) * CFG.gravity / v / RAD end
  local step = full * dt / CFG.rollTime
  A.turn = clamp(A.turn + clamp(want - A.turn, -step, step), -full, full)
  A.yaw = wrap180(A.yaw + A.turn * dt)
  local climb = clamp(climbCmd, -CFG.maxDive, CFG.maxClimb)
  if not flying then climb = clamp(climb, 0, 8) end
  A.climb = A.climb + clamp(climb - A.climb, -CFG.pitchRate * dt, CFG.pitchRate * dt)
  local y, c = A.yaw * RAD, A.climb * RAD
  local roll = math.atan(A.turn * RAD * A.speed / CFG.gravity) / RAD
  return A.speed * math.cos(c) * math.sin(y), A.speed * math.sin(c), -A.speed * math.cos(c) * math.cos(y), roll
end

-- Clearance above the terrain after t seconds on heading yaw+off at the current climb
function AI.probeClearance(me, A, hAt, t, off)
  local y, sp = (A.yaw + off) * RAD, math.max(A.speed, CFG.minSpeed)
  local hs = sp * math.cos(A.climb * RAD) * t
  local px, pz = me.x + math.sin(y) * hs, me.z - math.cos(y) * hs
  return me.y + sp * math.sin(A.climb * RAD) * t - hAt(px, pz)
end

-- Terrain between two points (sampled every 6 blocks): true when nothing is in the way
function AI.lineOfSight(a, b, hAt)
  local dx, dy, dz = b.x - a.x, b.y - a.y, b.z - a.z
  local n = math.max(1, math.ceil(math.sqrt(dx * dx + dz * dz) / 6))
  for i = 1, n - 1 do
    local f = i / n
    if a.y + dy * f < hAt(a.x + dx * f, a.z + dz * f) then return false end
  end
  return true
end

-- The safety layer: bank, climb, speed, why overriding the AI, or nil
--   terrain: clearance on the path within gcasTime below gcasMargin -> wings level, full climb (turn to the lower
--            side if even that would not clear it);  fence: far past the fence -> turn home
function AI.safety(me, A, hAt, home)
  local low = me.y - hAt(me.x, me.z) < CFG.gcasMargin * 0.7
  local t = 0.5
  while t <= CFG.gcasTime and not low do
    if AI.probeClearance(me, A, hAt, t, 0) < CFG.gcasMargin then low = true end
    t = t + 0.5
  end
  if low then
    local climbed = { speed = math.max(A.speed, CFG.minSpeed), yaw = A.yaw, climb = CFG.maxClimb }
    if AI.probeClearance(me, climbed, hAt, CFG.gcasTime, 0) >= CFG.gcasMargin then return 0, CFG.maxClimb, CFG.maxSpeed, "terrain" end
    local l = AI.probeClearance(me, climbed, hAt, CFG.gcasTime, -45)
    local r = AI.probeClearance(me, climbed, hAt, CFG.gcasTime, 45)
    return l > r and -CFG.maxBank or CFG.maxBank, CFG.maxClimb, CFG.maxSpeed, "terrain"
  end
  local dh = math.sqrt((me.x - home.x) ^ 2 + (me.z - home.z) ^ 2)
  if dh > CFG.fence * 1.3 then
    local err = wrap180(yawTo(home.x - me.x, home.z - me.z) - A.yaw)
    return clamp(err, -CFG.maxBank, CFG.maxBank), 0, CFG.maxSpeed, "fence"
  end
end

-- Observation: own state, 8 terrain probes, 2 target slots {x,y,z,vx,vy,vz,hp}, previous command
function AI.features(me, A, hAt, slots, ctx)
  local f = {}
  local function put(v) f[#f + 1] = v end
  local yaw = A.yaw * RAD
  local fwdx, fwdz, rgtx, rgtz = math.sin(yaw), -math.cos(yaw), math.cos(yaw), math.sin(yaw)
  local hx, hz = ctx.home.x - me.x, ctx.home.z - me.z
  local hd = math.sqrt(hx * hx + hz * hz)
  put(A.speed / CFG.maxSpeed); put(A.climb / 35); put(clamp(A.turn / 80, -1.5, 1.5)); put(clamp(hd / CFG.fence, 0, 2))
  put(hd > 1 and (hx * rgtx + hz * rgtz) / hd or 0); put(hd > 1 and (hx * fwdx + hz * fwdz) / hd or 1)
  put(clamp((me.y - ctx.home.y - CFG.patrolHeight) / 50, -2, 2)); put(ctx.heat); put(ctx.hp)
  for _, p in ipairs(AI.PROBES) do put(clamp(AI.probeClearance(me, A, hAt, p[1], p[2]) / 40, -1, 1.5)) end
  for i = 1, 2 do
    local t = slots[i]
    if not t then
      for _ = 1, 11 do put(0) end
    else
      local dx, dy, dz = t.x - me.x, t.y - me.y, t.z - me.z
      local d = math.max(math.sqrt(dx * dx + dy * dy + dz * dz), 1)
      local dhz = math.max(math.sqrt(dx * dx + dz * dz), 1)
      local rvx, rvy, rvz = t.vx - me.vx, t.vy - me.vy, t.vz - me.vz
      local closing = -(dx * rvx + dy * rvy + dz * rvz) / d
      local ts = math.sqrt(t.vx * t.vx + t.vy * t.vy + t.vz * t.vz)
      local aspect = ts > 0.5 and -(dx * t.vx + dy * t.vy + dz * t.vz) / (d * ts) or 0
      put(1); put(clamp(d / CFG.engageRange, 0, 2)); put((dx * rgtx + dz * rgtz) / dhz); put((dx * fwdx + dz * fwdz) / dhz)
      put(dy / d); put(clamp(closing / 30, -2, 2)); put(clamp(ts / 30, 0, 2)); put(aspect); put(clamp(dy / 100, -2, 2))
      put(t.hp); put(AI.lineOfSight(me, t, hAt) and 1 or 0)
    end
  end
  put(ctx.prev[1] / 60); put(ctx.prev[2] / 20); put(ctx.prev[3] / 2)
  return f
end

-- The network: layers W[l] (out x in, row-major) and b[l], ReLU, linear outputs
function AI.forward(net, x, acts)
  local a = x
  if acts then acts[1] = a end
  local L = #net.W
  for l = 1, L do
    local n, m, W, b = net.sizes[l], net.sizes[l + 1], net.W[l], net.b[l]
    local o = {}
    for j = 1, m do
      local s, r = b[j], (j - 1) * n
      for i = 1, n do s = s + W[r + i] * a[i] end
      if l == L or s > 0 then o[j] = s else o[j] = 0 end
    end
    a = o
    if acts then acts[l + 1] = a end
  end
  return a
end

-- Best action per branch (0-based, first on ties)
function AI.greedy(q)
  local out, o = {}, 0
  for h, n in ipairs(AI.BRANCHES) do
    local b = 0
    for k = 1, n - 1 do if q[o + k + 1] > q[o + b + 1] then b = k end end
    out[h] = b
    o = o + n
  end
  return out
end
-- AI CORE END

---------------------------------------------------------------- brain
-- "in h1 h2 out|w w w ..." (each layer W then b)
local function parseBrain(s)
  local head, body = s:match("^([%d ]+)|(.*)$")
  if not head then return nil end
  local sizes = {}
  for n in head:gmatch("%d+") do sizes[#sizes + 1] = tonumber(n) end
  local nums, k = {}, 0
  for v in body:gmatch("%S+") do k = k + 1; nums[k] = tonumber(v) end
  local net, p = { sizes = sizes, W = {}, b = {} }, 0
  for l = 1, #sizes - 1 do
    local W, b = {}, {}
    for i = 1, sizes[l] * sizes[l + 1] do p = p + 1; W[i] = nums[p] end
    for j = 1, sizes[l + 1] do p = p + 1; b[j] = nums[p] end
    net.W[l], net.b[l] = W, b
  end
  if p ~= k or sizes[1] ~= 9 + #AI.PROBES + 22 + 3 then return nil end
  return net
end

local function copyNet(net)
  local c = { sizes = net.sizes, W = {}, b = {} }
  for l = 1, #net.W do
    c.W[l], c.b[l] = { table.unpack(net.W[l]) }, { table.unpack(net.b[l]) }
  end
  return c
end

local BRAIN_INIT -- the pre-trained network, set just before the program starts (see BRAIN BEGIN)

local function loadBrain()
  if fs.exists(CFG.brainFile) then
    local f = fs.open(CFG.brainFile, "r")
    local net = parseBrain(f.readAll() or "")
    f.close()
    if net then return net, "learned" end
    log("%s is not a brain for this program, using the built-in one", CFG.brainFile)
  end
  if BRAIN_INIT then return parseBrain(BRAIN_INIT), "built-in" end
end

local function saveBrain()
  local net = S.brain
  local f = fs.open(CFG.brainFile .. ".tmp", "w")
  f.write(table.concat(net.sizes, " ") .. "|")
  for l = 1, #net.W do
    for _, arr in ipairs({ net.W[l], net.b[l] }) do
      local parts = {}
      for i = 1, #arr do parts[i] = ("%.7g"):format(arr[i]) end
      f.write(table.concat(parts, " ") .. " ")
    end
  end
  f.close()
  if fs.exists(CFG.brainFile) then fs.delete(CFG.brainFile) end
  fs.move(CFG.brainFile .. ".tmp", CFG.brainFile)
end

local function yieldNow()
  os.queueEvent("uav_learn")
  os.pullEvent("uav_learn")
end

-- One online double-DQN step on a small batch from the replay (plain SGD, Huber, gradient clipping)
local function learnOnce()
  local net, tgt, R = S.brain, S.target, S.replay
  local G = { W = {}, b = {} }
  for l = 1, #net.W do G.W[l], G.b[l] = {}, {} end
  local nb = CFG.learnBatch
  for _ = 1, nb do
    local tr = R[math.random(#R)]
    local y = tr.r * CFG.rewardScale
    if tr.next then
      local qa, qt = AI.forward(net, tr.next), AI.forward(tgt, tr.next)
      local ga, m, o = AI.greedy(qa), 0, 0
      for h, n in ipairs(AI.BRANCHES) do m = m + qt[o + ga[h] + 1]; o = o + n end
      y = y + CFG.gamma * m / #AI.BRANCHES
    end
    local acts = {}
    local q = AI.forward(net, tr.obs, acts)
    local d, o = {}, 0
    for j = 1, #q do d[j] = 0 end
    for h, n in ipairs(AI.BRANCHES) do
      local j = o + tr.act[h] + 1
      d[j] = clamp(q[j] - y, -1, 1) / #AI.BRANCHES
      o = o + n
    end
    for l = #net.W, 1, -1 do
      local n, m, W, a, gW, gb = net.sizes[l], net.sizes[l + 1], net.W[l], acts[l], G.W[l], G.b[l]
      local dn = {}
      for i = 1, n do dn[i] = 0 end
      for j = 1, m do
        local dj = d[j]
        if dj ~= 0 then
          gb[j] = (gb[j] or 0) + dj
          local r = (j - 1) * n
          for i = 1, n do
            gW[r + i] = (gW[r + i] or 0) + dj * a[i]
            dn[i] = dn[i] + W[r + i] * dj
          end
        end
      end
      if l > 1 then for i = 1, n do if a[i] <= 0 then dn[i] = 0 end end end
      d = dn
    end
  end
  local norm = 0
  for l = 1, #net.W do
    for _, g in pairs(G.W[l]) do norm = norm + (g / nb) ^ 2 end
    for _, g in pairs(G.b[l]) do norm = norm + (g / nb) ^ 2 end
  end
  local sc = CFG.learnRate / nb * math.min(1, 10 / math.max(math.sqrt(norm), 1e-9))
  for l = 1, #net.W do
    local W, b = net.W[l], net.b[l]
    for i, g in pairs(G.W[l]) do W[i] = W[i] - sc * g end
    for j, g in pairs(G.b[l]) do b[j] = b[j] - sc * g end
  end
  S.updates = S.updates + 1
  if S.updates % CFG.targetEvery == 0 then S.target = copyNet(net) end
  yieldNow() -- one update is well within CC's time slice; let the flight and lasers run in between
end

local function learner()
  while S.running do
    if S.learning and S.brain and #S.replay >= CFG.learnStart then
      for _ = 1, CFG.learnUpdates do learnOnce() end
      if os.clock() - S.lastSave > CFG.saveEvery then
        pcall(saveBrain)
        S.lastSave = os.clock()
        log("brain saved (%d updates)", S.updates)
      end
    end
    sleep(CFG.decideEvery)
  end
end

---------------------------------------------------------------- flight
local function hdist(a, b) return math.sqrt((a.x - b.x) ^ 2 + (a.z - b.z) ^ 2) end

-- Bank / climb / speed that fly towards a point (the hand-written pilot: h, r, and when there is no brain)
local function steerTo(me, A, P, speed, bankLimit)
  local dx, dy, dz = P.x - me.x, P.y - me.y, P.z - me.z
  local hz = math.sqrt(dx * dx + dz * dz)
  local v = math.max(A.speed, CFG.minSpeed)
  local maxTurn = math.tan(bankLimit * RAD) * CFG.gravity / v / RAD
  local turn = hz > 1 and clamp(wrap180(yawTo(dx, dz) - A.yaw) * 1.5, -maxTurn, maxTurn) or 0
  local bank = math.atan(turn * RAD * v / CFG.gravity) / RAD
  return bank, clamp(atan2(dy, math.max(hz, 1)) / RAD, -CFG.maxDive, CFG.maxClimb), speed
end

-- Circling clockwise (seen from above) around a centre: a point a little ahead on the circle
local function circlePoint(me, c, radius, alt)
  local a = atan2(me.z - c.z, me.x - c.x) + math.min(math.pi / 2, 30 / radius)
  return { x = c.x + math.cos(a) * radius, y = alt, z = c.z + math.sin(a) * radius }
end

-- The two targets the AI sees: its focus first, then by threat
local function slots()
  local list = {}
  local focus = S.chase and S.byId[S.chase]
  if focus then list[1] = focus end
  for _, t in ipairs(S.targets) do
    if #list >= 2 then break end
    if t ~= focus and t.kind == "sublevel" then list[#list + 1] = t end
  end
  if not focus and list[1] then S.chase = list[1].id end
  return list
end

-- An AI decision (every CFG.decideEvery s): store what happened since the last one, pick the next command
local function decide(me)
  local sl = slots()
  local fs2 = {}
  for i, t in ipairs(sl) do
    fs2[i] = { x = t.x, y = t.y, z = t.z, vx = t.vx, vy = t.vy, vz = t.vz, hp = t.mass and S.maxMass[t.id] and t.mass / S.maxMass[t.id] or 1 }
  end
  local heat = 0
  for _, L in ipairs(S.lasers) do heat = heat + (L.heat or 0) end
  local ctx = { home = S.home, heat = heat / #S.lasers, hp = S.mass0 and me.mass and math.min(1, me.mass / S.mass0) or 1, prev = S.prev }
  local obs = AI.features(me, S.air, heightAt, fs2, ctx)

  -- Reward since the last decision (same terms as the simulator)
  local r = S.reward - REWARD.time
  if S.flag.terrain then r = r - REWARD.terrain end
  if S.flag.fence then r = r - REWARD.fence end
  local near = false
  for _, t in ipairs(S.targets) do if t.range < CFG.engageRange * 1.5 then near = true end end
  if not near then
    r = r - 0.002 * math.max(0, hdist(me, S.home) - CFG.patrolRadius * 1.5) - 0.002 * math.min(math.abs(me.y - S.home.y - CFG.patrolHeight), 50)
  end
  S.reward, S.flag = 0, {}
  if S.lastObs then
    S.replay[S.replayNext] = { obs = S.lastObs, act = S.lastAct, r = r, next = obs }
    S.replayNext = S.replayNext % CFG.replaySize + 1
    S.returnAvg = S.returnAvg * 0.99 + r * 0.01
  end

  local q = AI.forward(S.brain, obs)
  local act = AI.greedy(q)
  if S.learning then
    for h, n in ipairs(AI.BRANCHES) do if math.random() < CFG.explore then act[h] = math.random(0, n - 1) end end
  end
  if act[4] == 1 and sl[2] then S.chase = sl[2].id end
  S.cmd = { AI.BANKS[act[1] + 1], AI.CLIMBS[act[2] + 1], AI.SPEEDS[act[3] + 1] }
  S.prev = { S.cmd[1], S.cmd[2], act[3] }
  S.lastObs, S.lastAct = obs, act
  local best, o = 0, 0
  for h, n in ipairs(AI.BRANCHES) do best = best + q[o + act[h] + 1]; o = o + n end
  S.q = best / #AI.BRANCHES
end

-- The hand-written pilot for h (circle here), r (go home) and when there is no brain
local function manual(me)
  local A = S.air
  local ground = heightAt(me.x, me.z)
  if S.hover then
    S.mode = "CIRCLE"
    return steerTo(me, A, circlePoint(me, S.hover, CFG.patrolRadius * 0.6, math.max(S.hover.y, ground + 20)), 16, 30)
  end
  local alt = math.max(S.home.y + CFG.patrolHeight, ground + 20)
  if S.rtb then
    S.mode = "RTB"
    if hdist(me, S.home) < CFG.patrolRadius then S.rtb = false end
    return steerTo(me, A, { x = S.home.x, y = alt, z = S.home.z }, CFG.maxSpeed, 30)
  end
  S.mode = "PATROL"
  return steerTo(me, A, circlePoint(me, S.home, CFG.patrolRadius, alt), 16, 30)
end

-- One flight step: both commands in the same tick
local function fly()
  local me = S.self
  if not me then
    S.mode = "NO SHIP"
    return
  end
  local now = os.clock()
  local A = S.air
  local dt = clamp(now - A.time, 0.05, 0.3)
  A.time = now
  local bank, climb, speed
  if S.brain and not S.hover and not S.rtb then
    if now >= S.nextDecide then
      decide(me)
      S.nextDecide = now + CFG.decideEvery
    end
    S.mode = "AI"
    bank, climb, speed = S.cmd[1], S.cmd[2], S.cmd[3]
  else
    bank, climb, speed = manual(me)
  end
  local sb, sc, ss, why = AI.safety(me, A, heightAt, S.home)
  if sb then
    bank, climb, speed = sb, sc, ss
    if why == "terrain" and me.y - heightAt(me.x, me.z) > 15 or why == "fence" then S.flag[why] = true end
    S.override = why
  else
    S.override = nil
  end
  local vx, vy, vz, roll = AI.airStep(A, bank, climb, speed, dt)
  local yaw, pitch = A.yaw, A.climb
  local last = S.att
  local turn = not last or math.abs(wrap180(yaw - last[1])) > 0.5 or math.abs(pitch - last[2]) > 0.5
    or math.abs(roll - last[3]) > 0.5
  if turn then S.att = { yaw, pitch, roll } end
  local jobs = { function() core.setVelocity(vx, vy, vz) end }
  if turn then jobs[2] = function() core.setRotation(yaw, pitch, roll) end end
  parallel.waitForAll(table.unpack(jobs))
end

---------------------------------------------------------------- lasers
-- Where to aim now: the target's world position extrapolated from the last scan (world velocity), plus the
-- lead for the aim to take effect, which uses the velocity relative to our own ship (the laser moves with it)
local function predicted(t, o)
  local age, lead = os.clock() - S.scanTime, CFG.lead
  return t.x + o[1] + t.vx * age + t.rvx * lead, t.y + o[2] + t.vy * age + t.rvy * lead, t.z + o[3] + t.vz * age + t.rvz * lead
end

local function aim(L, t, idx)
  local o = L.stick and L.target == t.id and L.stick
  if not o and idx > #t.points then
    if not L.rand or L.randIdx ~= idx then L.rand, L.randIdx = randomPoint(t), idx end
    o = L.rand
  end
  return L.p.aimAt(predicted(t, o or t.points[idx] or t.points[1]))
end

-- Within CFG.gunCone of the nose, like guns fixed in the nose
local function inCone(t)
  local me, A = S.self, S.air
  if CFG.gunCone <= 0 or not me or not A then return true end
  local dx, dy, dz = t.x - me.x, t.y - me.y, t.z - me.z
  local d = math.sqrt(dx * dx + dy * dy + dz * dz)
  if d < 1e-6 then return true end
  local y, c = math.rad(A.yaw), math.rad(A.climb)
  local dot = (dx * math.cos(c) * math.sin(y) + dy * math.sin(c) - dz * math.cos(c) * math.cos(y)) / d
  return dot >= math.cos(math.rad(CFG.gunCone))
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

local function inReach(L, t)
  -- From where the laser is now: its offset from the radar (measured while it was aiming) plus the latest scan
  local o = S.radarPos
  if not o then return true end
  local off = L.offset or { 0, 0, 0 }
  local dx, dy, dz = t.x - o.x - off[1], t.y - o.y - off[2], t.z - o.z - off[3]
  local half = t.size and math.max(t.size.x, t.size.y, t.size.z) / 2 or 1
  return math.sqrt(dx * dx + dy * dy + dz * dz) - half <= L.reach
end

-- All lasers start on the ship being chased, then by threat
local function pickTarget(L, i, now)
  local order = {}
  local chased = S.chase and S.byId[S.chase]
  if chased then order[1] = chased end
  for _, t in ipairs(S.targets) do if t ~= chased then order[#order + 1] = t end end
  local tries = 0
  for _, cand in ipairs(order) do
    if tries >= CFG.maxTries then break end
    if not (L.skip[cand.id] and L.skip[cand.id] > now) and inReach(L, cand) then
      tries = tries + 1
      -- Lasers on the same target start on different aim points: each laser burns its own block
      local idx = ((i - 1) % #cand.points) + 1
      if aim(L, cand, idx) then
        L.target, L.aimIdx, L.miss, L.lastOn, L.stick, L.settle = cand.id, idx, 0, now, nil, CFG.settleTicks
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
  local heat = L.p.getHeat()
  L.heat = heat
  if L.p.isOverheated() or heat >= CFG.heatHigh then L.cooling = true
  elseif heat <= CFG.heatLow then L.cooling = false end

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
      L.status = #S.targets > 0 and "WAIT" or "IDLE"
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

  if S.armed and on and not L.cooling and inCone(t) then
    if not L.firing then
      L.firing = L.p.fire() == true
      if not S.armed then cease(L) end
    end
    L.status = "FIRE"
  else
    cease(L)
    if not S.armed then L.status = "HOLD"
    elseif L.cooling then L.status = "COOL"
    elseif on then L.status = "AIM" -- on target but outside the gun cone
    elseif hit and hit.type ~= "none" then L.status = "BLOCKED"
    else L.status = "TRACK" end
  end
end

---------------------------------------------------------------- display
local screen = term.current()
local buffers = {}
local classColor = { HOSTILE = colors.red, FRIEND = colors.lime, UNKNOWN = colors.lightGray, DEAD = colors.gray }
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
  local me = S.self
  line(1, (" UAV %-6s %s  burnt %d"):format(S.mode, S.armed and "ARMED" or "HOLD", S.totalBurnt),
    colors.white, S.armed and colors.red or colors.gray)
  if me then
    local spd = math.sqrt(me.vx ^ 2 + me.vy ^ 2 + me.vz ^ 2)
    local att = S.att or { 0, 0, 0 }
    line(2, ("pos %6.0f %4.0f %6.0f spd %4.1f bank %+3.0f pitch %+3.0f"):format(me.x, me.y, me.z, spd, att[3], att[2]), colors.lightGray)
  else
    line(2, "not on a ship", colors.orange)
  end
  if S.brain then
    local c = S.cmd
    line(3, ("AI %s bank %+3d climb %+3d spd %2d Q %+5.1f %s"):format(S.brainFrom, c[1], c[2], c[3], S.q,
      S.learning and ("learn %d"):format(S.updates) or "fixed"), S.override and colors.orange or colors.cyan)
  else
    line(3, "no brain: hand-written pilot", colors.orange)
  end
  if S.override then line(3, ("SAFETY %s: pull up / turn"):format(S.override), colors.orange) end
  local y = 4
  for _, L in ipairs(S.lasers) do
    local col = ({ FIRE = colors.red, COOL = colors.lightBlue, BLOCKED = colors.orange, TRACK = colors.yellow, AIM = colors.yellow })[L.status] or colors.lightGray
    line(y, ("%-10s %-7s %3d%% %s"):format(L.name, L.status, math.floor((L.heat or 0) * 100), L.label or ""), col)
    y = y + 1
  end
  line(y, (" contact         class  dist  alt  spd"), colors.lightGray, colors.gray)
  y = y + 1
  for _, t in ipairs(S.contacts) do
    if y > h - 1 then break end
    local alt = me and (t.y - me.y) or 0
    local chased = S.chase == t.id
    line(y, ("%s%-15s %-6s %5.0f %+4.0f %4.0f"):format(chased and ">" or " ", label(t):sub(1, 15), t.class:sub(1, 6), t.range, alt, t.wspeed),
      classColor[t.class])
    y = y + 1
  end
  if #S.contacts == 0 then line(y, " no contacts", colors.lightGray) end
  line(h, " spc arm h circle r home f/e mark l learn x reset q", colors.lightGray, colors.gray)
  win.setVisible(true)
  win.setVisible(false)
end

---------------------------------------------------------------- loops
local function setArmed(a)
  S.armed = a
  log(a and "ARMED" or "HOLD")
  if not a then
    local jobs = {}
    for _, L in ipairs(S.lasers) do jobs[#jobs + 1] = function() L.firing = false; L.p.stop() end end
    parallel.waitForAll(table.unpack(jobs))
  end
end

-- Radar and flight: scan (1 tick), fly (1 tick), draw
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
    if S.forget then
      S.forget = false
      if fs.exists(CFG.brainFile) then fs.delete(CFG.brainFile) end
      S.brain, S.brainFrom = loadBrain()
      S.target, S.replay, S.replayNext, S.lastObs = copyNet(S.brain), {}, 1, nil
      log("forgot what was learned: back to the %s brain", S.brainFrom)
    end
    fly()
    draw(screen)
    if monitor then draw(monitor) end
  end
end

local function combat()
  local jobs = { radarLoop, terrainLoop, learner }
  for i, L in ipairs(S.lasers) do
    jobs[#jobs + 1] = function() while S.running do engage(L, i) end end
  end
  parallel.waitForAll(table.unpack(jobs))
end

-- Keys only flip flags here: peripheral calls in this coroutine would swallow events
local function events()
  while S.running do
    local ev = { os.pullEvent() }
    if ev[1] == "laser_break" then
      S.totalBurnt = S.totalBurnt + 1
      local sub = ev[7]
      if sub then S.burnt[sub] = (S.burnt[sub] or 0) + 1 end
      if sub and S.seenHostile[sub] then
        S.reward = S.reward + REWARD.dealt
        S.hurt[sub] = os.clock()
      end
    elseif ev[1] == "laser_overheat" then
      log("%s overheated", ev[2])
    elseif ev[1] == "key" then
      local k = ev[2]
      local top = S.contacts[1]
      if k == keys.space then setArmed(not S.armed)
      elseif k == keys.h then
        if S.hover then S.hover = nil
        elseif S.self then S.hover = { x = S.self.x, y = S.self.y, z = S.self.z } end
        log(S.hover and "circle here" or "resume")
      elseif k == keys.r then
        S.hover, S.rtb = nil, true
        log("return home")
      elseif (k == keys.f or k == keys.e) and top then
        local class = k == keys.f and "FRIEND" or "HOSTILE"
        mark(top.id, class)
        log("marked %s (%s) %s", label(top), top.id, class)
      elseif k == keys.c then
        if fs.exists(CFG.marksFile) then fs.delete(CFG.marksFile) end
        S.marks, S.seenHostile = {}, {}
        log("marks cleared")
      elseif k == keys.l and S.brain then
        S.learning = not S.learning
        log("learning %s", S.learning and "on" or "off")
      elseif k == keys.x and S.brain then
        S.forget = true -- done by the radar loop (it owns the brain between decisions)
      elseif k == keys.q then
        S.running = false
      end
    elseif ev[1] == "monitor_touch" then
      setArmed(not S.armed)
    end
  end
end

-- After a world or chunk load the ship can take a moment to appear: wait up to 30 s
local function waitForShip()
  core.setEnabled(true)
  for _ = 1, 60 do
    local self, active
    parallel.waitForAll(function() self = radar.getSelf() end, function() active = core.isActive() end)
    if self and active then return self end
    term.clear()
    term.setCursorPos(1, 1)
    print(not self and "Waiting for the ship (radar not on a ship)..." or "Waiting for the anti-gravity core (off, redstone, or another core on this ship)...")
    sleep(0.5)
  end
  error("No ship: the radar and an active anti-gravity core must be on the same ship", 0)
end

local function start()
  loadMarks()
  local self = waitForShip()
  S.self = self
  S.home = loadHome()
  if not S.home then
    S.home = { x = self.x, y = self.y, z = self.z }
    saveHome(S.home)
  end
  S.brain, S.brainFrom = loadBrain()
  if S.brain then
    S.target = copyNet(S.brain)
    S.learning = CFG.learn
    S.lastSave = os.clock()
  else
    log("no brain: flying by hand-written rules")
  end
  -- The aircraft starts with the ship's heading and speed (standing still on the ground: a take-off run)
  local rot
  parallel.waitForAll(
    function() rot = core.getRotation() end,
    function() core.setGravity(0) end,
    function() core.setLimits({ speed = CFG.maxSpeed, acceleration = CFG.acceleration, angularSpeed = CFG.turnRate }) end)
  local speed = math.sqrt(self.vx ^ 2 + self.vy ^ 2 + self.vz ^ 2)
  local yaw = rot and rot.yaw or 0
  if self.vx ^ 2 + self.vz ^ 2 > 4 then yaw = yawTo(self.vx, self.vz) end
  S.air = { speed = speed, yaw = yaw, climb = 0, turn = 0, time = os.clock() }
  log("start: %d lasers (reach %.0f), range %d, home %.0f %.0f %.0f, %s, brain %s%s", #S.lasers, reach, CFG.range,
    S.home.x, S.home.y, S.home.z, S.armed and "armed" or "hold", S.brainFrom or "none", S.learning and " (learning)" or "")
end

-- BRAIN BEGIN (generated by tools/uav_ai/export.js: the pre-trained network)
BRAIN_INIT = "42 64 64 13|-0.03077166 0.139795 0.01835301 0.09257595 -0.09619949 -0.08336537 -0.09755811 0.7990729 -0.2109721 0.4023209 -0.118291 -0.09342727 0.2573337 -0.09956515 -0.02887512 -0.1448647 -0.1167158 -0.03095079 0.171375 0.1598441 0.2315709 -0.4427402 -0.1405967 0.3128397 -0.08218962 0.1178749 0.04725705 0.2208961 -0.1755703 -0.8964266 -0.05766798 -0.1436977 0.1096404 0.414961 1.25775 0.1265306 -0.0466186 -0.7154362 -0.05377196 -0.002050123 0.003279424 0.003524589 0.287325 0.01464868 0.3050155 -0.03267907 0.02559647 0.09826382 -0.1104658 -0.2157651 -0.3857866 0.07806933 -0.05091384 -0.09301778 0.09475848 0.08160159 0.03966925 -0.01841764 -0.1286823 -0.07576102 -0.04786586 0.1740178 -0.2290309 -0.3289524 0.3652299 -0.5411914 0.04326544 0.2109551 -0.03959612 0.4997104 0.4166207 -0.2718148 0.4148811 -0.1387194 -0.1427232 -0.1856236 0.4091399 0.02793086 0.0514963 -0.955148 0.3576665 0.0765982 0.03817765 -0.02689262 0.1833553 -0.03020314 -0.01197266 0.1405692 0.1000188 0.07592884 0.1280438 0.079985 0.09184578 -0.2134337 0.2818725 0.07491924 -0.0325205 -0.3104736 0.1976731 -0.1340224 -0.07793936 -0.4039858 0.3367878 -0.1086887 -0.168627 -0.1167756 -0.1063093 1.07072 -0.2173623 -0.1544374 0.1760749 0.1944834 -0.01926427 0.6684959 -0.06976679 -0.1739037 -0.2302402 -0.2135942 -0.2093058 -0.05404862 -0.1312907 0.2791452 -0.02291452 0.08427595 0.0393818 -0.01165737 0.05161487 0.07909736 -0.6691773 0.2926021 -0.1198219 -0.1099026 0.02179318 -0.1074429 0.1731351 -0.04589591 -0.1074874 0.1951328 0.1640867 -0.1653498 -0.07802078 -0.002119723 -0.1486319 -0.4105648 0.2392363 0.7404476 0.2506972 0.1414382 -0.05590493 -0.09377125 -0.007247054 -0.11545 0.2597726 0.1779503 -0.2595278 0.2850974 -0.1967409 -0.1090584 0.2085775 -0.01898282 0.01453536 -0.1618676 0.02771815 0.2458785 -0.03436524 -0.2011703 -0.01844265 -0.03524448 -0.08267258 -0.174915 0.1576529 -0.4527924 0.1224827 -0.353533 -0.2094331 0.05349336 -0.03199177 -0.255957 0.3007989 -0.1079081 -0.08213318 -0.1605236 -0.0750591 0.196725 0.3073815 0.3267717 -1.446839 -0.128668 -0.244451 -0.1362952 -0.3771771 0.1393035 -0.144748 0.5215114 -0.5894279 0.155161 0.3038435 -0.4490287 0.2631996 -0.3078042 0.2530821 -0.1553858 -0.1193379 0.006929826 -0.1654693 0.2335807 0.3499465 0.08681587 -0.06052845 0.00007893524 -0.02466464 -0.02610031 0.00981692 0.5006952 -0.02841235 -0.0691189 0.00009511497 0.03061832 -0.1131983 -0.2200157 0.1497856 0.1183281 0.08948988 -0.1687975 -0.02793237 0.02563816 -0.04517645 0.5305643 -1.66675 -0.08207009 0.1255138 -0.5508159 0.2317989 -0.6507111 -0.01843689 0.2482819 0.5215207 0.416118 0.06406185 -0.06156658 0.008704966 -0.08799916 -0.1990637 0.01336318 0.09363192 -0.1035272 0.2613593 0.4889698 -0.022996 -0.01072418 -0.003159289 0.008634742 0.3032103 0.02659339 -0.01506544 0.9001898 -0.01932259 0.01455818 0.02313159 -0.3873978 -0.4149227 -0.2062319 0.08151569 -0.1496587 0.1863913 -0.1609165 -0.004047448 0.3276879 -0.2541831 -0.148029 0.07050429 -0.1131595 0.05529214 -0.5050527 -0.1301646 -0.2927495 0.05926695 -0.05268657 0.7698025 -0.307105 0.04275196 1.167909 0.007707281 -0.08815586 0.7556177 -0.3770912 -0.350007 -0.08553054 -0.2592016 -0.3702041 -0.05943508 -0.007059176 -0.0113448 -0.03583342 0.1340599 -0.08215025 -0.05902713 0.9138279 0.07870484 0.05435063 0.1483842 0.05529178 -0.3355863 -0.1084222 0.1640752 0.1596324 -0.2522134 -0.18948 0.1824578 0.08081454 0.1589734 -0.1675848 -0.1254306 -0.0424857 -0.08697376 0.08962816 -0.04865759 0.03327465 -0.09196454 0.1415189 -0.2326793 -0.1290153 -0.2727 0.4384314 0.00499051 -0.09667289 0.7778618 0.5224731 1.149641 0.02497697 0.1905013 -0.4437277 -0.07564016 -0.01063463 0.02221268 -0.01400091 -0.2890708 -0.004326022 0.101648 0.7922017 -0.07857528 -0.1216451 0.01638627 0.2019301 0.2887698 -0.02382507 0.1428946 -0.06866875 -0.355974 -0.1813691 0.1917202 0.2024025 0.1808654 0.4821571 -0.4310109 -0.1187288 -0.174115 0.4066959 0.2233054 0.2496879 0.2040761 -0.1065971 -0.1403517 0.3349427 -0.4763976 -0.1325654 -0.02341794 0.3221364 -0.04918797 -0.2550313 0.8705778 0.1796744 -0.1595069 -0.1823379 0.06262956 0.03262012 -0.01063087 0.000387441 -0.2285461 0.08833244 -0.0359283 1.359601 0.04096215 0.004390385 0.1064347 0.2727252 -0.2212132 0.32243 -0.1716673 0.125424 0.1174521 0.2343058 -0.04727107 -0.2107973 0.1463183 -0.09458747 -0.006945976 -0.04140872 0.1957988 -0.08576712 0.0546865 0.2091432 -0.5193189 0.0005358297 -0.2887671 -0.1713517 0.1078173 -0.05468225 -0.03510096 0.2032983 -0.3599517 -0.09512828 0.01435191 -0.5929548 -0.2067449 -0.59157 0.1051682 -0.02277756 0.06102455 -0.09445528 0.2866191 -0.2067842 0.1337673 -0.3939747 0.199781 0.152767 -0.1421996 -0.2058662 0.6717245 -0.1341516 -0.1704852 0.1616919 0.1206643 -0.174565 0.03698141 0.03623516 0.1345306 0.3658915 0.2923555 -0.05204378 0.208514 -0.1560808 -0.3059375 0.5735867 0.1236783 -0.1680144 -0.2672409 -0.1140015 0.3812993 -0.09693299 -0.1273808 0.1354374 0.1581605 -0.1490501 0.3418333 0.1031672 -0.02232298 -0.5833965 0.4244321 0.009430168 -0.01791317 -0.01079773 0.1497835 0.1257696 -0.2897373 0.05958508 0.06378749 -0.1566162 -0.1143507 0.7137038 0.05302746 0.1003907 -0.2453419 -0.1767643 0.02992116 -0.1279954 -0.001218851 0.3816903 -0.1719664 -0.4717683 -0.2627842 -0.319567 -0.1078611 -0.3141422 0.1087943 0.001668608 -0.1129476 -0.259515 -0.01360204 -0.3276934 0.2114691 0.948723 0.3451437 0.1178438 0.01091488 -0.3620591 -0.008366905 -0.002882001 -0.2804827 0.2565338 -0.08737482 -0.04848116 -0.01763432 -0.007856091 -0.3210369 0.05993717 -0.02879257 0.736187 0.03598442 -0.1194052 0.0771104 -0.2540055 -0.1624927 -0.003011623 0.1390181 0.02145704 0.1320206 -0.05829655 -0.05406181 -0.07523179 -0.03803949 0.5098202 -0.2243512 0.01951283 -0.07427163 -0.1454319 0.5177708 1.580102 0.3822712 -0.09229093 -0.12854 0.0354334 -0.199735 0.01679349 0.125699 -0.04987398 0.018211 0.1654301 0.3056953 -0.08492689 0.02313926 -0.2232814 -0.3111129 -0.02462552 0.01166121 0.002183395 0.03566239 -0.03725874 0.0009059487 -0.1359976 0.0128211 -0.005245197 0.03757967 0.4105562 -0.2522155 -0.08072397 -0.04070619 0.1378644 -0.05879106 -0.04658576 0.002543743 -0.06373049 0.09938051 0.5422623 -0.329107 -0.000454335 0.1962653 -0.4267323 -0.09391557 -3.603221 0.1151297 0.1260938 0.7108263 0.02543714 -0.2128544 0.03236857 -0.008392548 -0.05112971 0.1814794 -0.016917 0.02575538 0.0023977 0.06634668 0.1822561 0.1104926 0.001878652 0.01296129 -0.01280962 0.002262804 0.0002670742 -0.3880796 0.1891894 -0.6035842 -0.06344223 0.04000623 0.09405705 -0.2292878 0.1521048 0.06941974 0.1470519 -0.03093894 -0.130473 0.2164911 -0.01739048 -0.1481366 0.2297503 -0.02283501 -0.3326587 -0.05926205 0.3130318 -0.04929404 0.1101949 0.1809208 -0.06864901 -0.573113 -0.1553443 -0.1602088 -0.0314164 -0.2153554 0.1047304 0.10144 -0.3369521 -0.2428288 0.2424768 -0.1476199 -0.2591978 0.2413588 -0.05686275 -0.007733764 -0.01527074 0.2025693 -0.06845565 0.07469129 0.1103621 0.02542128 0.00296505 -0.0843373 -0.01101621 0.7346394 0.06826236 0.09964066 0.186205 0.0989316 -0.01547334 -0.03096232 -0.06208783 0.006521307 -0.4270362 0.03507418 -0.05897218 0.1066019 0.06960291 0.131948 0.975604 -0.03497525 -0.1067857 -1.103819 -0.1075813 -0.3777431 -0.2456774 0.3572096 0.06665619 0.004121671 0.03058957 -0.07981284 -0.405156 0.3090148 -1.233277 -0.8106009 0.005058533 -0.002799431 -0.02677929 -0.1797008 0.05732213 -0.08797329 -0.005767854 0.09274233 0.2206839 -0.1141343 -0.2342243 0.1891112 -0.2188441 0.01117326 0.2000363 -0.06637647 -0.1690832 0.006300307 0.02746151 -0.09233625 0.08666499 -0.006349878 0.1088544 0.1396804 -0.2458345 -0.07586001 0.3338242 0.02921693 0.3265494 0.628121 -0.1760325 -0.1246295 -0.2579486 -0.1054182 -0.1316879 0.6632513 0.004713562 0.8624436 -0.002557906 0.1155216 0.5150082 0.4301986 -0.0275511 -0.05141848 -0.002523417 0.1721749 0.4482154 0.002101837 0.9318121 0.1187359 -0.2701683 -0.4056133 0.07423878 -0.2323732 -0.09203663 0.198969 -0.0005950175 -0.5414847 -0.1805673 0.01211768 0.01452494 -0.09516015 0.4356123 -0.3180706 0.002786895 0.128981 0.1285481 -0.2247042 0.3267991 -0.03657903 0.06987401 -0.3620523 0.08483027 -0.2947964 -0.3134293 0.06940904 0.09200749 -0.2155644 -0.4340247 0.3663096 -0.2894492 -0.2595205 -0.07211967 -0.1569067 0.02906576 -0.009486142 0.004064807 -0.4379219 0.02047341 0.0007370813 -0.429357 -0.1271711 0.01365969 0.02806027 0.7301525 0.1939134 -0.1319881 -0.08558176 0.1066584 -0.06129074 -0.2659841 -0.198214 0.2927976 0.1510093 0.3212037 0.6718541 0.0163105 -0.1804556 0.08405762 0.08079708 0.4796887 -0.1580475 0.05596138 0.2495399 0.05965219 -0.2408036 1.209284 0.1166849 0.05734277 0.2126361 -0.4318798 -1.273557 -0.04644422 -0.3116294 0.3946214 -0.004619652 -0.02563178 -0.00563344 -0.01341193 0.01491677 -0.0004777752 -0.01326344 0.1312419 -0.01268913 0.03743863 -0.1973399 0.1779465 -1.725484 0.1830758 0.2858591 -0.09867212 0.1117166 -0.1702824 0.09781257 0.08249512 0.03482933 0.3101554 -0.7119173 0.104336 -0.1420717 -0.2523582 0.1084328 -0.2033145 -0.1169799 -0.07885629 -0.4470362 0.07710283 0.2693174 -0.164209 -0.06601372 -0.01530693 -0.730105 0.2854648 0.1504613 0.02086122 0.5059564 0.07783699 0.3375041 -0.007217606 0.01334819 -0.01362412 -0.02613193 -0.2017351 0.1786002 0.6643542 -0.136302 -0.2961819 0.0785123 -0.4494205 -0.08992617 0.0836969 -0.3049124 0.09718439 -0.01308902 -0.05536141 0.1013237 0.1253272 0.0804667 -0.4383466 0.4080483 -0.1793409 0.1116868 0.04976127 0.06390444 0.6036911 -0.4394923 0.07990132 0.06136509 0.1093976 -0.3464248 0.1082445 0.1442291 0.01570218 0.04171904 0.004992185 -0.2963327 -0.2096135 -0.0419048 0.3215802 -0.04173453 0.01337996 0.004089891 0.04976416 0.09004096 -0.01829047 0.06557933 -0.1227458 0.06600543 -0.06156346 -0.1030518 0.1628777 0.08938724 0.1485571 -0.2698976 0.2541177 0.0768762 -0.2468598 -0.04966636 -0.01397117 -0.02959383 0.4989602 -0.388166 0.2791395 -0.1346633 0.2233996 0.5047782 1.184278 0.18295 -0.1786648 -0.3237548 -0.08608422 -0.174441 0.2014275 0.08136485 0.3410918 -0.1073611 -0.1696315 -0.5074959 0.0549919 -0.1210531 0.3189454 -0.01958152 -0.01484663 -0.008068823 -0.005204867 -0.1052129 0.1456671 -0.1844006 0.2822493 0.240123 -0.2262944 -0.05325975 0.07060621 -0.9551056 -0.03275177 -0.02415274 -0.1581859 -0.3312918 0.2643206 -0.1750019 -0.1307538 -0.1533011 -0.1990558 0.192189 -0.3288468 0.1591148 -0.003817554 -0.4846164 -0.09904925 -0.05603498 0.2535075 0.3266488 0.1629688 0.4058605 -0.175288 -0.02097613 -0.06747931 -0.2988934 0.2378179 -0.6284604 -0.07244661 0.08889869 0.5308587 0.2001753 0.04137833 -0.04239765 -0.07905166 0.2360396 0.03725704 0.08427203 0.1026072 0.06003736 0.1222305 -0.05692747 0.3090921 0.075961 0.2281936 -0.2815624 -0.1124787 -0.3333744 -0.03376215 0.1860408 0.1081797 0.1864203 0.4901004 -0.2283344 -0.02227819 0.09876529 -0.04167097 -0.04912051 -0.6838118 0.1931386 -0.006401433 0.3740814 -0.04301272 0.688416 -0.6579012 -0.02974381 0.1783098 -0.5704321 0.5049587 -0.1239361 0.2465904 0.1621095 -0.03008671 0.4540923 0.01181229 -0.01050349 -0.006831944 0.1591874 0.03759877 -0.3962141 -0.2251388 -0.03428685 -0.2984934 -0.0626341 0.07068814 -0.09879398 -0.17644 0.2374257 0.2666627 -0.03037062 -0.1530229 0.1047347 -0.08384096 -0.3190665 0.1049403 -0.1816444 -0.8160239 -0.5631796 0.0343471 -0.1036476 0.2184767 0.1493573 0.08847598 -0.181535 0.07260949 0.2946403 -0.1831637 -0.8596783 -0.4092129 -0.3002156 -0.1525563 0.3653644 0.1479457 -0.1196245 -0.1706165 0.103431 0.02711585 0.001641291 -0.04953322 0.392689 -0.1499692 -0.06300518 -0.0228363 -0.057669 -0.07379809 0.1136535 -0.007141228 0.7523438 0.1119634 -0.038771 -0.007747357 -0.01139422 -0.1647536 0.2652799 -0.2392184 0.1609219 -0.4767547 -0.3764807 -0.01814137 -0.07213785 -0.07240778 0.06342615 -0.5088401 0.08595863 -0.0463569 -0.3298367 0.1802371 -0.3069107 0.1887295 0.1616344 0.06530691 -0.01212496 -0.2764171 -0.4989973 -0.002915736 -0.1835335 -0.08638909 -0.09324338 0.03892908 -0.006574387 0.04202819 0.0684928 -0.03490733 0.8328598 0.0914299 0.05228406 0.02975222 0.003520278 -0.05204359 -0.1750213 -0.2451726 0.1210563 -0.1966662 -0.2864025 0.1095754 0.2219101 0.152756 0.06460696 -0.4021068 -0.0240681 0.1126581 0.1085688 0.05311097 -0.03586682 0.04518571 -0.007460822 0.1271522 -0.2214096 -0.07540924 0.1868227 0.6148393 -0.9282746 0.2114586 -0.3061306 -0.08015043 0.03802926 -0.1645937 -0.2512761 0.2018344 -0.2205556 0.1507569 0.02476218 0.05233876 -0.1341681 -0.04451414 -0.1225052 -0.2639127 0.01098355 0.005811977 0.08213046 -0.1728575 0.239504 0.1144885 -0.09209683 0.2051484 -0.2225635 -0.1068388 0.1108709 0.05776479 0.07074847 0.6947612 -0.7792798 0.0371487 0.203174 -0.02253272 0.03715418 0.5284483 -0.05903003 -0.1468467 0.3032145 0.2321289 -0.4413683 0.4718853 0.0980431 -0.1727791 -0.3176968 -0.3663797 -0.09576869 -0.3480666 -0.03049927 0.1711163 -0.3393411 -0.02257981 0.02574525 -0.008737836 0.2921997 -0.01677536 -0.02728252 0.04234021 -0.09970873 -0.004538868 0.02798359 0.1489664 -0.08770059 0.1813423 0.2569212 -0.2287779 0.06849293 -0.147195 0.03061843 -0.09063724 0.2368414 -0.6500623 0.5344666 -0.004158076 0.1774175 0.2038715 -0.2409783 0.3565939 0.09735015 0.4411141 0.3142473 0.203369 -0.2923395 0.262229 0.1387554 -0.01265712 -0.06617597 -0.121669 -0.007387335 0.08929689 0.2466883 0.3578651 -0.2439956 -0.001897402 -0.03977148 -0.00609466 -0.008821098 -0.01391057 0.05980246 0.100066 0.01200175 -0.03162923 -0.0731172 0.2334164 -0.2131449 -0.02449989 -0.001926004 0.07906751 -0.03674098 -0.06055375 -0.00002117946 0.1390197 0.03407683 0.8193992 -0.3077585 -0.006770994 -0.09994296 -0.06065162 0.1681214 -0.2195581 -0.01855579 0.2477331 0.1390327 0.2168966 0.16283 -0.6040327 0.03142037 0.3374856 -0.1977747 -0.1483759 -4.019242 0.1969818 -0.2055553 0.3734283 -0.106582 0.003959162 0.007994929 -0.005677962 0.126587 0.04102651 0.05645607 -0.08044383 -0.03653947 -0.1286689 0.1205698 0.3559865 0.4365868 -0.3770596 0.2875932 -0.2731655 0.1494018 0.04990449 -0.04824245 0.2004113 -0.09954321 0.4482242 0.2322378 -0.1325841 0.08579123 -0.109903 -0.1435479 -0.313781 -0.116928 0.153059 0.4058548 -0.02086101 0.6261528 -0.5651229 -0.15015 -0.218412 -0.08564244 0.1437186 0.05220032 0.1108756 0.1169066 0.01343847 -0.1886976 0.01571624 -0.01137668 -0.00443204 -0.1631493 0.03989554 -0.06705717 -0.5207436 -0.09184143 0.05951308 0.08506646 -0.4237986 0.2163381 0.314464 -0.2245691 0.2554208 0.1812271 -0.06900481 -0.1449253 -0.2200624 0.0652366 0.4983858 0.1087416 -0.01562148 -0.08887693 -0.1469992 0.2208852 0.4110171 -0.06538122 0.2340733 -0.1031477 0.3198301 0.597148 -0.05149396 0.1404585 0.1310857 0.1634509 -0.08019263 0.0287161 0.1522082 -0.2354037 0.08690726 -0.001851608 -0.007937545 0.001114261 -0.02768276 -0.1928463 -0.1051613 -0.01100138 -0.3283504 0.001164751 0.1489085 -0.08486681 0.01377596 -1.223376 0.03074795 -0.06579623 0.2206262 0.1424846 0.1153168 0.04124419 -0.05244539 -0.2048925 0.2699578 -0.8853859 0.1201646 0.01611831 0.2948716 0.3740131 0.06912207 -0.07141708 0.01563448 0.5055887 0.1642826 0.2749177 0.08433806 -0.001187082 -0.01675448 0.6113167 -0.1519647 0.1473524 -0.05219691 0.3094405 0.7397561 -0.2301049 0.005552664 -0.007610057 0.02032152 0.1005667 0.01239923 0.04712705 0.6096221 0.02548728 -0.06486086 0.1024024 -0.6468436 0.3582987 0.03377008 -0.142101 0.1460753 -0.04969779 -0.02783887 -0.03265468 -0.03875812 0.03588589 -0.2004261 0.7550424 -0.02642145 0.1788432 -0.4943886 -0.2495599 -0.2747198 0.02269054 0.1292957 -0.3225248 0.2648175 -0.5087766 0.4678175 -0.04875058 -0.06251776 -0.4164696 0.03147624 -0.4097863 0.007809932 0.2638576 -0.9499205 0.1078759 -0.01532755 0.00007745528 -0.02526954 -0.03202183 -0.06313548 0.05228762 0.1993873 -0.07776041 -0.06480137 0.07360151 -0.3469054 -0.2873932 -0.0002322898 0.219436 0.05364538 -0.1944992 -0.112664 0.1528992 0.03849231 0.06759978 0.2788061 -0.5007283 0.04472535 0.0583946 0.1366203 -0.2772338 0.8496447 -0.2102915 0.2101048 0.1149671 0.4153083 -0.05023897 -0.2208147 0.007150806 0.2153738 0.4344429 0.08574789 -0.4552965 0.1444869 0.09469945 0.09050318 0.3811841 -0.006039349 0.004044211 0.03735848 -0.02933326 0.07642824 0.0008870657 0.5504281 0.05203009 -0.04532022 0.09288892 -0.2279218 0.1367687 -0.01411608 0.1894933 0.1356472 -0.3282158 -0.303789 0.1435933 0.2087523 -0.01720346 0.007637426 0.8862027 -0.0305791 -0.0978328 0.7415771 -0.2909043 0.2183096 -0.09866606 -0.3753299 -0.1830712 -0.1954208 0.5965239 -0.3239641 0.01566437 0.0518061 -0.07794687 0.3652253 0.2974914 0.2712746 -0.3354672 -0.6309505 -0.003469134 0.03979483 0.01051372 0.006252092 0.1558178 0.08599018 0.1491501 -0.4543464 -0.01415112 0.2549786 -0.05113195 0.03641129 0.3067344 0.05916597 0.1589901 -0.3223062 0.2021595 0.2739637 -0.06176012 -0.1448551 -0.115559 0.01229014 -0.4454335 -0.2550387 0.02558046 0.04018011 -0.05771062 1.238325 0.2088699 0.01614645 0.3039345 0.2123006 -0.1041117 0.05685074 0.2005998 -0.0324893 0.1517092 -0.01124987 -0.3216094 -0.1541482 0.403524 -0.1088469 0.3259846 0.003931695 -0.02909444 -0.03223447 -0.08686983 0.2298051 0.6650247 0.2970961 0.1169632 0.06878478 0.02784493 -0.5952545 -0.3563071 0.04780352 -0.02482219 0.1652924 0.0001883738 -0.0968579 -0.151457 -0.1317366 0.05554221 -0.04016693 0.5111088 -0.7729616 -0.4140341 0.5857411 0.1212187 -0.3622578 -0.2280074 -0.1796315 0.4245829 -0.008118093 0.3430601 0.3280512 0.1610561 0.003268639 0.4434507 -0.04400403 -0.1666324 0.009244435 -0.1892425 0.1955881 0.1806908 0.02751555 -0.03554305 -0.1172553 0.04579379 0.06461722 -0.02737608 0.009902182 -0.02321867 0.01749003 -0.02683228 0.04753505 -0.1633906 0.08644564 0.3453984 -0.09552128 -0.02058686 -0.2247834 0.1519148 -0.08032659 -0.04495966 0.2755997 0.09214571 0.04292836 0.336499 -0.4967868 -0.1606151 -1.614101 0.3305751 -0.3735865 -0.06461573 0.4169222 -0.5299968 -0.4073862 0.002890452 0.04423409 0.4972939 0.1633088 0.3056937 -0.2232713 0.3546533 -0.2094283 -0.2093001 -0.01687409 0.01449715 0.00154841 -0.1025851 0.004432114 -0.0009706249 -0.2985657 -0.1050333 -0.04936811 -0.02254891 0.1955783 0.5567291 -0.01739226 -0.1922365 0.03816471 0.04793771 0.136912 -0.1808503 0.1932513 0.04026776 -0.02845415 -0.5826126 -0.00416774 0.02782241 -0.4431826 0.240197 0.7882623 0.08917754 0.1763632 -1.079454 0.3183767 -0.3237729 0.3511602 -0.1419471 -0.05486493 -0.2528598 -0.2133569 -0.4218681 -0.1184132 0.4990363 0.3888418 -0.02213873 -0.005260803 0.01754816 0.003855754 0.09136653 -0.01590277 -0.1025147 0.1501531 -0.05144364 0.004773504 -0.1541049 -0.1739764 0.2145044 -0.105101 -0.008784451 0.002758913 0.1916013 0.1240001 -0.07324427 0.009717321 -0.2084857 -0.3454362 0.4338264 0.001082196 0.07477962 -0.3909521 -0.1478236 1.01275 -0.1582462 -0.1405436 0.8272933 0.02098364 0.3550156 -1.150156 -0.001771425 0.1377269 -0.2888164 0.2648515 0.01002841 0.04039749 0.1279795 -0.7672842 -0.1179104 0.01774665 0.01084426 -0.00957766 -0.01126923 -0.04680127 0.0217764 -0.1907804 0.04693983 0.03256156 -0.03279092 -0.3497924 0.07822508 -0.1640325 0.1071098 0.1630207 0.1298461 -0.09230046 -0.1164332 -0.05175357 -0.02602548 -0.4087885 1.166238 0.01536015 -0.1012647 0.5791181 -0.395347 0.1104284 -0.1116488 -0.5643138 0.07839154 -0.1308312 0.3158325 -0.3596148 -0.113725 0.02219632 -0.1868745 0.4132131 0.3700491 0.06803362 0.3257837 -0.9231965 0.1269422 0.008951617 -0.003630767 -0.01621461 -0.1295725 -0.01721302 0.01359064 -0.1692303 -0.06928858 0.01260358 0.0146262 0.6511619 -0.6734984 -0.08386778 -0.1090261 -0.02082528 0.003814404 -0.02519236 0.01581787 0.2241955 -0.008422558 0.002023474 -0.119728 -0.003994688 -0.03042091 -0.05412882 -0.02082819 0.4820395 -0.01993194 0.08810063 0.6339103 -0.366508 0.9785114 -1.002779 -0.03134578 -0.06267728 -0.5160681 0.4156993 -0.03057166 0.04130799 0.1333734 0.148724 0.4282885 -0.003932371 -0.006528375 0.00354212 0.2140609 0.03440059 0.1774677 0.1133955 -0.007505824 0.1388828 0.02630054 -0.00897095 0.2809377 -0.05890996 0.1194249 -0.05004547 0.04374987 0.1241888 -0.03958638 -0.08885715 0.009457365 0.4321053 -0.2844975 -0.02123637 0.1280664 -0.2792255 0.5352406 -0.04580757 0.2587449 0.09486933 0.4016663 -0.05780948 0.03947221 0.5448892 -0.3060389 0.1688328 -0.02328315 -0.1825009 -0.02883005 0.3232394 -0.3413515 -0.6739535 -0.2012146 -0.004373899 0.0009847993 -0.03607574 0.1894646 -0.1430682 -0.05978064 0.0586253 -0.1970063 0.04477666 -0.09684593 -1.934522 -0.6159477 -0.1423678 0.1619335 -0.04551017 0.09012702 -0.1884547 0.1164722 0.2403527 -0.1331155 0.2350349 0.6187432 -0.02408045 -0.04310039 0.3387329 -0.3492972 -0.7295745 -0.002304077 0.0110763 0.05828218 0.1368341 -0.05941915 0.6689426 0.08882363 0.08617247 0.3142437 -0.4571002 -0.590879 -0.02625357 0.08151921 -0.06953133 -0.02802168 0.005269094 0.00007854604 -0.03818474 -0.3423003 0.09242846 0.1716682 0.1280714 -0.01951585 -0.3566301 0.1756369 0.07728645 0.0476518 -0.1120657 0.1488894 0.1595147 0.1808287 -0.2272209 -0.08206052 -0.1690564 0.007666067 0.2354885 0.1129098 0.4696487 -0.1107747 0.2140556 -0.1125566 -0.102906 -0.04099736 -0.01448095 0.273953 -0.465634 0.04185207 -0.1870323 0.6913766 -0.06949482 -0.4693013 0.1044117 0.5472888 0.3869982 -0.0855805 -0.1312371 0.05125334 0.00893043 0.0009596853 -0.008495752 0.08987458 -0.04147182 -0.00917287 0.108608 -0.007146303 0.02247793 0.1686092 -0.4124653 0.4673267 -0.0603897 -0.297768 0.04774788 0.1795006 0.08076656 -0.1086937 0.08922117 -0.1168239 0.3392326 -1.161867 0.09155275 0.2398344 0.08029724 0.1468024 0.7434306 0.06593801 0.04089115 -2.171678 0.6427907 0.04666551 0.3522573 -0.05109693 0.229079 -0.2437874 -0.2462728 -0.1624526 0.03014902 0.1233528 0.3473097 -0.06805216 -0.0260465 0.004953442 0.02183458 0.1243278 0.007421366 -0.0393417 0.02593621 0.07137308 -0.01364569 -0.08358077 0.3887279 0.6086276 0.05238514 -0.1838991 -0.1968116 0.06884872 0.1029216 -0.05455691 0.03087324 0.0328063 0.5266136 0.6804717 -0.06911817 0.06928566 -0.4379861 -0.1467083 -0.02617959 -0.03042365 0.04582312 0.010875 0.09217082 -0.4378498 -0.8832132 -0.0264843 0.2321959 -0.3667099 0.1205593 -1.522308 0.2200833 -0.007045682 -1.142226 -0.2670758 0.03664724 -0.009808187 -0.02933657 -0.2838448 0.1826134 -0.3113672 -0.2504622 -0.07395536 0.01659525 0.02030275 -0.1505187 0.7484223 -0.09097984 0.1077658 -0.1225336 0.1742101 0.02243542 -0.2417145 -0.2942802 -0.1766205 -0.1476354 -0.3419302 0.1027238 0.1304822 -0.2692787 -0.09917599 0.07232507 0.01358874 -0.4096838 0.2888138 -0.3594864 0.1926423 -0.6145487 -0.3111321 0.4111121 0.02866522 0.1249215 0.2801035 0.04226549 -0.3422536 0.4083206 -0.005737262 -0.0364327 0.0498359 0.08988901 -0.1477915 -0.004656585 0.01207558 0.30054 0.0791003 0.009197363 -0.04987671 -0.3097391 0.2195816 0.09494069 -0.02024775 -0.0112977 0.1159341 -0.01607373 -0.110382 0.08760306 -0.03878852 -0.4882378 0.1756114 -0.1054191 0.03302612 -0.4450943 -0.02556146 -0.9998851 0.08004549 0.2259239 0.6769204 0.1760801 0.6386543 -1.06001 0.1113966 0.1213442 0.2400047 0.2300249 0.5524826 0.02303231 -0.1551498 -1.293123 0.7889724 -0.0228238 0.01185345 -0.004469771 0.1750887 -0.1097337 0.7963049 -0.1082964 0.2994701 0.04219141 -0.09759525 0.2865693 -0.7589913 -0.2048213 0.1581608 0.1813334 -0.1538452 0.2286456 -0.131929 -0.226645 0.2311829 -0.1517227 -0.07335462 0.5406077 -0.4813888 -0.3261738 -0.1413348 0.5213189 0.3906402 -0.01252262 -0.2144888 0.08578303 -0.06534938 -0.06254594 0.2467591 -0.3554442 -0.372029 0.05444064 -0.3766712 0.0744441 0.1024452 0.2090503 0.2468579 0.04701111 0.04847962 -0.006507661 0.08191137 -0.04399944 0.0005679711 0.4314247 -0.1741492 -0.176209 -0.02778098 0.04557205 -0.08073036 0.1377998 0.0534345 -0.01952063 0.2413859 -0.03767918 -0.2411852 -0.1330037 -0.0905379 -0.686353 0.4904608 0.1106808 -0.3795143 0.02091102 -0.09706016 -0.3123291 0.01306966 -0.3489695 0.3440018 -0.06153398 0.3254181 0.4046654 -0.008179726 -0.2692683 -0.1005552 -0.2037289 0.8313059 -0.3009899 -0.04917624 0.4427056 -0.1903504 -0.01098202 0.02369168 0.1504316 -0.3167374 -0.1274598 -0.08762439 -1.158304 -0.08654439 0.2796459 -0.07620533 0.5376684 0.3369284 -0.05124363 -0.1519314 0.2160907 0.02846459 0.1295681 -0.03370618 0.1072008 -0.1194732 0.05753083 0.1173133 -0.0535427 -0.1477995 -1.343254 -0.09484614 -0.4908462 -0.07926527 0.4710002 0.4435043 0.2034038 0.2005716 0.376154 0.002037014 0.0513068 0.03795456 -0.2222842 -0.05514142 -0.3286438 -0.0374517 -0.287619 0.2073241 -0.01598101 0.02746153 -0.04698289 -0.2118091 0.05453972 0.1237936 -0.3819615 0.05791042 0.04617153 -0.04224133 -0.08958364 -0.5044343 0.154535 -0.2753062 0.05982609 0.05964942 0.03149974 -0.1643608 0.01233885 0.02743543 -0.1634693 0.4230283 -0.03567656 -0.3147032 -0.4444195 0.3374127 -1.060596 0.2842305 -0.1980719 0.5469741 -0.2098632 0.2090716 0.1711427 0.05458134 -0.1980234 0.0414554 0.2791084 -0.5448545 -0.05773998 -0.09248152 0.3988445 0.3529101 -0.006222376 -0.00945988 0.00236059 -0.348088 0.1584479 -0.1740137 -0.0973991 0.008111057 0.05399357 0.0876118 -0.318275 0.09907821 -0.09982264 -0.007579138 0.01360711 -0.02057038 -0.1932654 0.04113194 0.1174657 -0.1320344 -0.04259677 -0.7001977 -0.07902045 -0.1055148 0.2751963 0.1708185 -0.009154002 -0.0438354 0.2671828 -0.6832495 -0.06240197 0.5181098 0.7558038 0.07955634 -0.2274212 -0.6023493 0.2095648 0.07002511 0.1274671 -0.05387056 0.8832286 0.1858008 -0.01611307 -0.03009333 -0.02791692 -0.1034331 0.02614049 -0.009319112 -0.1316762 -0.02243679 -0.02100476 0.08717303 0.5916606 -1.08631 0.05678288 -0.1171517 0.2647055 0.05333336 0.01249174 -0.1688771 -0.1046749 0.04008434 0.3366928 -0.9619272 -0.06290737 -0.03255105 -0.2073723 0.5032576 0.1583968 0.08441928 0.1484869 -0.2716544 0.3836665 0.4393094 -0.7430708 -0.04648103 -0.1221873 -0.210217 0.3189346 0.02643202 -0.08644677 0.2011644 0.480662 0.1888782 0.004010816 0.007552436 -0.03624885 -0.07262432 0.1162393 -0.01871767 -0.04806027 -0.007484159 -0.3724169 0.003699713 -0.05884659 -0.119226 0.02765147 0.2934628 0.06387569 -0.08240835 -0.3035633 0.02714309 -0.2298609 0.05575094 0.6005167 -0.1515822 0.04244276 -0.1252211 0.06034087 -0.03467068 0.1259951 0.1271265 -0.01474342 -0.3567158 -0.002680266 0.7630047 -0.4042475 0.003200232 -0.1274909 -0.1978258 0.0003904743 -0.421087 -0.1835243 0.2410874 -0.5071094 0.5375866 0.0298613 -0.02032454 0.009120078 -0.1314927 0.003892415 -0.007645086 -1.112214 0.03663662 -0.005977991 -0.1804027 0.7698144 -0.1351377 -0.04779856 -0.08568007 0.03920636 0.02906833 0.2038299 -0.1190424 -0.04638581 0.03916453 0.04956054 0.292456 0.02986124 -0.1725362 -0.291951 -0.002330954 -0.3230292 -0.100531 0.3935021 -0.3765522 0.1170968 -0.1117037 -0.01326041 0.05600921 0.281637 -0.1222085 0.04127184 0.3995625 -0.08313126 0.02741796 0.02940487 0.4554988 0.002230053 -0.002050297 -0.03879659 0.01539907 -0.00880924 0.4779518 -0.2120381 0.3469786 0.02750322 0.08977467 -0.3891218 -0.3516963 -0.07742713 0.2403656 -0.3854702 0.0001586711 0.1358884 -0.1474803 0.1989466 0.1397127 0.4038141 -0.2500396 0.1816685 -0.142694 0.5349524 0.2735716 0.04791877 -0.09855467 0.007624833 -0.4832192 0.02643503 -0.1207 0.3190485 -0.6016327 -0.1580197 0.2539726 0.00001074356 -0.3602021 0.01404065 0.05189355 0.2770019 0.02087886 0.06993986 -0.008381689 -0.01607688 0.01459871 -0.01163659 0.01640146 -0.7229969 0.01614197 0.03362367 0.04548544 0.1813252 -0.1601733 0.03096777 -0.1580026 0.1938059 0.008433617 0.03496648 -0.04810536 -0.1112682 0.004557784 -0.289348 0.1142084 -0.08807946 0.02571396 -0.2508287 -0.1062019 -0.002175695 -0.0262468 -0.1128507 0.6102243 0.01498491 0.7539042 -0.4015421 -0.06181026 -0.05354806 0.196095 0.362987 1.381839 -0.08583677 0.1236225 -1.350256 0.3233281 0.03032394 0.009455125 0.003436438 0.1821365 -0.02164893 -0.02620276 -0.0701094 -0.03506985 -0.07730532 -0.06502795 0.153161 0.3869347 0.135523 -0.2556842 0.1513892 0.04798054 -0.06356105 -0.01054506 0.07493692 -0.03651907 0.9320138 -1.215723 0.01248966 0.1111463 0.00554973 0.1822197 0.2368263 -0.07607611 -0.1115392 -0.832501 0.3383653 -0.4456476 0.5280528 0.0770039 -0.09713333 -0.4528943 -0.04546375 0.04298789 0.03831723 -0.1595066 0.5176631 -0.291579 -0.007377643 0.007120749 -0.008845394 0.06248595 0.03080454 -0.1017975 -0.1721033 -0.06808882 0.03192944 0.1178338 -0.383812 0.1911343 0.0125472 -0.03598838 0.08077342 0.06181769 -0.1363707 0.03630409 -0.1020285 -0.05079578 0.6998396 -0.4313161 -0.1995833 -0.002542839 0.4066288 0.596035 -0.3413806 0.04302613 0.4218577 -0.3369217 0.1891626 -0.4006921 -0.08787986 0.1686 -0.2360719 0.1989532 -0.07712593 -0.2040565 0.008630532 -0.2325919 0.458958 -0.103519 -0.0004895209 -0.02420878 0.006762775 -0.6434832 -0.0119821 0.2447894 -0.3181517 0.03676709 -0.02857033 0.1189309 -0.1743711 0.01413058 -0.02830943 0.147562 -0.04351964 -0.1609913 -0.1877679 0.05007246 0.3293718 0.0108005 -0.3065961 0.4000798 -0.1210938 -0.001911778 0.4652657 -0.1344264 0.4366231 -0.2869432 0.2010864 0.2536848 0.07723602 0.7507288 -0.6273383 0.1457186 0.2013761 -0.5309806 0.2007079 0.6744258 -0.1465248 0.05471573 0.2453839 -0.0938047 0.04704301 -0.01501137 0.008961794 0.1439623 -0.1226696 -0.01842718 -0.2999304 -0.01785637 0.05963568 -0.1611729 -0.1539086 -0.07635884 -0.04997004 0.165509 0.2105655 0.2866979 0.05583131 -0.07299989 -0.2094863 -0.1536436 0.6341722 -0.2614036 0.089095 -0.01724478 -0.2691928 -0.06328621 -0.7905565 -0.02360822 -0.0884163 0.3696412 0.3183726 0.5185071 -0.2452891 0.04905534 0.0502713 0.1894457 0.04770537 -0.4748606 -0.08680913 0.2191245 0.6443434 -0.127831 0.01671102 0.03036483 0.01512854 -0.2044487 0.1824762 -0.2883252 -0.3027228 0.03448943 0.2260599 0.2841118 -0.1532166 0.03320066 -0.1487778 0.1409977 0.01854566 -0.1493411 0.02134079 -0.02478835 0.7064936 -0.1273619 0.2884664 0.002980689 0.04662468 -0.08782958 -0.2052674 0.07068324 0.1194003 -0.07074599 0.3904048 0.09411612 0.1786128 0.06440424 0.2694217 0.08250867 0.2068455 0.05910396 -0.01546637 0.02390173 0.135496 0.004200135 -0.3613939 0.4310784 0.2892119 -0.06961955 -0.1387412 -0.335968 0.01985521 -0.02754616 0.05218638 0.3913764 0.1512867 -0.05273539 0.1298226 -0.1053849 -0.3339131 -0.1647364 -0.001783298 0.09849163 0.1554276 0.1766391 0.07874122 0.3341425 -0.1827043 0.04376193 0.2000567 -0.197048 0.3175208 0.03245697 0.3919575 -0.3315129 -0.2547602 0.3243413 0.5004719 -0.05121694 0.2464372 0.284488 0.3242514 -0.194169 0.4005948 0.379728 -0.2250925 0.3579844 0.006428955 -0.2092977 -0.08802882 -0.1057963 0.5341364 0.3492113 -0.05570213 0.1715473 -0.007494446 -0.170313 0.1484043 0.2595218 -0.0971241 -0.3011938 -0.06130044 0.1510581 0.2758429 0.08591224 0.006899659 -0.01837169 -0.1018095 0.1314093 0.08296576 -0.0503143 0.1783223 0.1009728 -0.02295688 0.3524712 0.2544191 -0.8019866 0.340457 0.2673766 -0.2847647 0.3018776 0.2647254 0.355255 -0.03868076 -0.1553612 -0.05203944 0.1310919 0.1487023 -0.1205252 0.04087166 -0.005207666 -0.1894917 0.2760626 0.3773137 -0.1510139 0.1271816 -0.05682256 0.4028208 -0.20315 -0.178531 0.008633251 0.1458699 -0.4662085 0.2842295 -0.3033783 0.5608118 -0.2427127 -0.3960119 0.3448934 0.519574 0.1166815 -1.246922 0.3902358 0.4175066 -0.544707 0.4044077 0.4194464 -0.06781412 0.3351202 -0.07260628 0.2593246 -0.1431696 -0.7304618 -0.06121125 -0.2266606 1.446778 -0.07241038 -0.08078843 0.3185105 -0.1759976 -0.06443607 -0.2052375 0.2599029 -0.2269104 0.3965616 -0.1300715 -0.2353013 -0.868652 0.6141137 -0.5267972 0.03202684 -0.647232 -0.5099128 -0.9012362 0.6068646 -0.3748807 0.4195632 -0.5519632 -0.5518073 -0.4322694 -0.6022193 0.6069498 0.2738839 -0.1075801 -0.02440013 -0.4101347 -0.212851 0.01526448 0.1702352 -0.007633551 -0.08447406 -0.3877464 0.2907195 0.1424473 -0.358292 0.4798472 0.2748685 -0.01997515 0.2485677 0.3487694 -0.4093373 0.2933238 -0.3990835 -0.05084748 -0.3744788 0.07449957 0.1586955 0.4037625 0.2399239 -0.07633108 0.4495007 -0.1627341 0.06982618 -0.1293162 -0.6459063 0.2944184 0.1181853 -0.3277917 0.2261721 -0.5505006 -0.2389633 -0.1800115 -0.08218261 0.1573747 -0.07580007 0.0533568 -0.1538633 0.1018875 -0.2242258 -0.1590755 0.04066415 0.1789416 -0.1482158 -0.150628 0.07025425 -0.2791769 0.3821029 0.3666209 -0.2320616 0.03527924 -0.0689009 0.2957267 0.2240238 0.0545315 0.485793 -0.5252885 0.02561241 0.4830798 -0.1031177 0.04005835 -0.4988571 -0.7303085 0.1892038 -0.1828105 -0.1080072 -0.2569149 -0.3900862 0.2035385 0.1178946 -0.2876467 -0.1216414 0.04993795 -0.05082343 0.116082 -0.1164835 -0.8746428 0.2229089 0.1827393 -0.05007671 -0.2441698 0.1849289 0.05295354 -0.02376616 -0.04208544 -0.04546605 -0.4818312 0.1532635 0.2967549 0.2435557 0.1279379 -0.4963642 -0.02439461 -0.04934551 0.1827578 0.2253046 -0.05302349 -0.5671378 0.1829892 -0.08139783 0.3204961 -0.1632104 -0.08445917 -0.2557631 -0.703996 0.06792644 -0.4200465 0.3228758 0.06530472 0.08090039 0.1072206 -0.8387984 0.1949057 0.2078361 -0.05208073 0.2865941 0.1212047 -0.3033751 -0.2884484 -0.4877583 0.1134398 -0.05317689 -0.2609846 -0.2109426 -0.03015647 -0.1211249 -0.536731 -0.1525666 0.2280249 0.02159192 -0.1651585 -0.02551607 -0.4605402 0.06950752 0.4075917 -0.06623104 -0.1602525 0.1194657 0.1039908 -0.1520891 -0.01300478 0.04994071 -0.4162863 -0.03967751 -0.5622452 0.01706938 0.3016224 -0.03181673 0.3786579 -0.1548854 0.3106926 -0.1262602 -0.09382677 0.2409154 -0.005919597 0.2073716 0.6120729 -0.179731 0.04975721 0.1378022 0.1053887 0.2224018 -0.2387684 -0.05850149 -0.3295353 0.2965505 -0.2531171 0.05654493 -0.001136946 -0.0279269 0.03046086 -0.4486826 0.1321367 -0.5002509 0.1126439 0.3430752 0.1588082 -0.1644317 -0.3881998 0.2958046 -0.377692 -0.09586514 -0.02334017 0.2715238 -0.3685109 0.2188162 0.2743167 0.06949414 -0.3204571 0.478088 0.3190316 -0.5005551 -0.02012275 0.1937019 0.4807835 0.02038993 0.07194765 0.02189567 0.3244001 0.158367 0.2480022 0.4584102 0.1595046 -0.0297435 0.0379524 0.01420977 -0.01282653 0.01576759 0.3108177 0.05825569 -0.1837403 -0.4381303 0.01725074 0.161365 -0.07850285 0.09212371 -0.02127094 0.1159316 -0.1146625 -0.134752 -0.7043371 0.06305074 -1.044435 0.0716385 -0.16566 -0.5592708 0.3444177 -0.1076174 0.2222931 -0.1875119 -0.05061085 -0.2250506 -0.1392929 0.1752899 -0.2146832 -0.07928723 0.1826115 0.2529204 0.005928923 -0.3402897 0.09258121 0.3669071 0.690649 -0.003387908 0.2065024 0.1329148 -0.3970961 0.2454685 0.1751521 -0.2346602 -0.1178553 -0.4391456 0.487112 -0.02957901 -0.3471175 0.3293742 -0.2976018 -0.3139228 0.2460137 -0.2843831 0.2472739 0.2908945 0.07000893 -0.6988544 0.3726827 -0.04086146 0.3073364 0.04557611 -0.3075379 -0.1520627 0.09229454 -0.05562397 -0.7464027 0.1195216 -0.1133178 -0.4153313 0.1816779 0.1069966 -0.03299673 0.5114544 0.02041755 0.1393324 0.1753044 -0.4133252 -0.2653928 -0.2726022 0.59931 0.2200823 0.1317128 0.8298892 0.01704745 -0.07389764 -0.073524 -0.1674084 0.136962 0.01012745 -0.01194394 0.1659739 0.5225888 0.3645138 -0.1439824 -0.73361 0.03892434 -0.6004876 -0.1512269 0.3168495 -0.05051014 0.2608908 0.04565456 0.009260675 0.3403369 -0.2064933 0.7644843 0.02244593 -0.04821933 -0.1609434 0.09630118 -0.4492797 -0.1871293 0.2190157 -0.05593982 0.1797883 -0.2063298 0.1066695 -0.1879998 0.002215506 -0.02460258 -0.1125066 -0.2379124 0.3705983 0.4602089 0.04388124 -0.1459432 0.119832 -0.5443975 -0.09142657 -0.2635062 -0.01667108 -0.3874522 0.2135563 0.4771353 -0.08353566 0.1609154 -0.02088005 -0.4573418 0.06088765 -0.3457697 -0.08664998 0.006100135 -0.2016591 -0.08934229 -0.3529765 0.2076741 -0.007534727 0.1113794 0.211611 0.1112758 0.4035894 -0.3108247 0.1169715 0.3477202 0.1427894 -0.3940497 -0.1198773 -0.1455146 -0.1410586 0.08322909 0.5116434 0.3204723 0.0367326 -0.1570809 0.2529272 -0.1578578 -0.0534485 -0.298621 -0.1196421 0.03919769 -0.01865104 -0.04560446 0.1522876 0.1068977 0.1654758 0.1383044 -0.2818477 -0.4151255 -0.3906288 0.1198627 -0.2390212 -0.3062823 0.1378098 0.2348985 -0.2078252 -0.009448327 0.2638063 0.1586729 -0.07464705 -0.07969563 0.4838442 0.01663804 0.00007436697 -0.6299065 0.09855299 -0.1335385 0.1387035 0.1865824 -0.2927046 0.1153401 -0.1822193 -0.1935834 0.3904239 -0.2868233 -0.202523 0.01980726 -0.1425225 0.2892423 0.03091987 0.04512433 0.04965574 -0.1136082 -0.1831504 0.02860109 0.2627067 -0.2110497 -0.05773387 -0.2037286 0.03952187 -0.3152666 0.3932851 0.1107108 0.2328196 0.04698801 0.06859156 0.07004248 -0.08114585 0.2535124 0.2334171 -0.2235012 -0.09432434 -0.07996138 -0.1228893 0.3155668 -0.06371866 0.07282045 0.05459411 0.1681886 0.5229272 0.1989211 -0.1261844 -0.03476353 -0.2066639 0.30567 0.1147692 -0.2436076 -0.3177745 0.2320738 0.0450469 -0.3439658 0.1142549 0.03065483 -0.06230232 0.2184407 -0.01416699 0.5033291 0.1495434 -1.670763 -0.2631277 0.1347569 -0.009279438 0.1779125 0.1384281 0.07085672 -0.1104023 0.1611736 0.2894557 -0.6135567 0.007194347 -0.1105722 0.2082748 0.3710243 -0.1234312 -0.4145146 0.2610795 -0.1007805 0.2422335 0.006978325 -0.1224749 0.1474862 0.002535724 -0.2308688 0.08286657 -0.1619415 0.5189084 -0.06095282 0.5090011 -0.1553814 -0.6056234 -0.1563281 0.1110711 -0.0756035 -0.01372199 -0.2384894 -0.07439669 -0.2085197 -0.4923673 0.7804942 0.1558705 0.0381024 -0.2430119 -0.2053103 -0.2632193 0.02579813 -0.2795562 0.2156749 0.2579089 -0.1560636 -0.2069657 -0.02827118 0.02573736 0.1655387 -0.2830423 -0.0007881879 -0.0643519 0.07842716 0.3489581 -0.07661803 0.1687677 -0.13462 0.09339081 -0.04080404 -0.09421254 0.01254078 0.194397 0.09287963 0.1382909 -0.0907517 0.1238135 0.3233276 -0.1114403 -0.1346962 0.07525762 -0.1676481 -0.07419087 -0.05167547 0.1314976 -0.06309877 0.1917128 0.03556782 0.1093165 -0.2314478 0.1428993 -0.001315139 -0.01302284 -0.02471459 0.2450973 -0.272012 -0.09949362 0.2302139 -0.07895357 0.2301168 -0.1338071 -0.2203823 0.08674629 0.3061501 -0.1501044 -0.3179564 0.2422635 0.1787575 0.3249192 -0.3211 -0.07094084 -0.05346508 -0.1289295 -0.3423281 0.3213211 0.250248 0.01838221 0.2122802 0.1986049 -0.08523948 0.04661514 -0.04774723 0.2913586 -0.6633608 0.1228774 -0.02700764 0.235925 -0.03828209 0.07891663 -0.3892532 -0.2959399 0.6205108 0.06250364 0.4131352 0.3468624 0.1399378 -0.08160067 -0.08143731 -0.3793177 0.192611 0.08753531 -0.3341581 -0.2374199 0.006527838 0.108158 -0.1803727 0.1175848 0.01222532 0.1578042 0.1983091 -1.199015 0.1318837 0.04940517 -0.1012095 -0.3774738 0.2187754 0.1302126 -0.2517359 -0.2692786 -0.5384035 -1.288003 -0.3337325 0.542042 -0.03941038 0.2666971 0.1305136 -0.3286903 -0.07304892 -0.1762718 -0.1673232 0.293166 0.2926796 -0.4016022 0.4816294 -0.4216693 -0.2991561 0.07930811 0.3328597 -0.01223686 -0.1827549 0.07060851 -0.01060135 0.3681735 0.2564232 -0.3438652 0.06871748 0.2470487 0.02380308 0.2095954 0.3038492 0.5313351 -0.007707094 0.03660604 -0.05733572 0.05579503 0.02932078 -0.1292164 0.3966096 0.5314069 0.3978015 -0.5619939 0.07638615 0.08735229 -0.1802499 -0.2051393 -0.4108382 0.1324389 0.02010079 0.04049382 0.04735736 0.09596116 -0.3028757 -0.2010042 0.2616504 -0.03801097 -0.03570277 -0.1210148 0.1748388 -0.09402648 0.02387178 0.04650345 0.03514547 -0.2090941 0.08077752 -0.6169133 -0.05855447 -0.08683478 0.1992845 0.04761124 -0.1011093 -0.1140323 0.2226922 -0.1003041 0.282281 0.1063763 -0.1088184 0.09966891 0.3059642 -0.2499835 0.2308262 -0.02597103 0.9085899 -0.02450085 -0.1751999 0.3594931 -0.1156789 0.2396231 -0.1889244 0.3725815 -0.1711363 -0.01012597 0.1637804 0.09202163 -0.1838627 0.08853948 0.1250717 0.07525876 0.3934707 0.06753388 0.3031405 0.09244589 -1.269413 -0.08290134 0.325428 -0.01603257 0.502253 -0.0936448 0.1168968 -0.08317627 -0.2628502 -0.2269575 -0.4299438 0.2295194 0.06041772 -0.01221232 -0.1365981 -0.2766209 0.234469 0.2472497 0.5547593 0.08132864 -0.3883443 0.06798207 -0.3223564 -0.4041553 0.1292511 0.1961063 -0.05761348 -1.041741 0.1182056 -0.3428469 -0.1038282 -0.3319724 -0.09253682 -0.3017151 -0.6108085 0.1747118 -0.1626761 -0.3066363 0.1297438 0.444227 0.178977 0.02389468 0.2684868 0.2726223 -0.1837747 0.02135714 0.2766596 -0.424134 -0.1746914 0.3168594 -0.1029476 0.3892997 -0.133563 -0.1260637 -0.2133011 -0.1640026 0.01312959 -0.1250241 0.08846304 -0.09614347 -0.09533419 0.509578 -1.559043 -0.02518029 -0.1358148 0.2932706 -0.201034 -0.04340751 0.05808191 0.2784366 0.3379816 0.1406196 -0.2371297 0.1101538 -0.1876679 -0.03273409 0.3045405 0.1383912 -0.05549258 -0.2566756 -0.03070166 0.3886411 -0.1874988 0.5803888 -0.1495248 0.2463998 -0.02847416 -1.577759 0.224686 0.5496925 0.3624445 0.1139559 0.121877 -0.04424624 -0.05846741 -0.6357204 -0.2489527 -0.02632168 -0.2253432 0.03988603 0.002395307 -0.05292069 -0.1884892 0.1210065 0.06367886 -0.08347631 -0.2720615 0.1267071 0.03697907 -0.1717021 0.128895 0.1510898 -0.555253 0.2316397 0.073819 -0.00590287 -0.0600733 -0.05163498 0.2073981 0.02667486 0.08949474 0.1293924 -0.2088842 -0.08892348 -0.03004512 -0.1299372 -0.3620128 0.07213729 -0.6405001 0.0719594 -0.008071006 -0.0003067791 0.09347855 -0.1489902 0.05725072 -0.2556848 -0.007361388 -0.01933686 0.1729171 -0.04672784 -0.2779014 0.2622034 -0.3234435 -0.2963861 -0.02641521 0.2847129 1.101982 -0.5308073 -0.08904611 0.1817348 -0.07541577 -0.1198213 0.2292808 -0.6007056 0.3844921 -0.05835065 0.2395726 0.2525295 -0.1784667 0.5209748 0.2891016 0.3109818 -0.1225357 0.09959002 -0.1473604 0.3154227 -0.01652726 0.2440847 0.2699948 -0.2899397 -0.01010484 -0.043916 -0.3453826 0.1716386 -0.1997175 0.09773462 -0.349865 0.1308487 0.07762916 -0.1440042 -0.1055401 -0.3788174 0.2349727 0.1583874 0.06004825 0.09512835 -0.0001196698 0.2350901 -0.05923407 0.4786569 -1.141563 0.1485097 -0.04852435 0.3803909 0.007997364 0.05445334 0.1264481 0.05124132 0.3690066 -0.3254202 -0.2289264 0.2061348 0.05428187 -0.03844734 0.0367587 0.3340221 0.1531 -0.2123759 -0.2315156 0.5856651 -0.1135157 0.04982717 0.09002098 0.5021971 -0.214414 -0.5067289 -0.1927338 0.2060157 0.06958028 0.1523159 0.3927968 -0.1247341 -0.02488323 -0.2712826 -0.07350755 -0.2243614 0.133663 0.2111073 -0.05792335 0.08371006 -0.1983168 -0.2159219 -0.09954455 0.09175877 -0.4888473 0.02521023 0.3562912 0.008177074 -0.02670217 -0.1086492 -0.1007414 0.234135 0.03602515 0.3085282 -0.1105385 -0.2766169 -0.1925705 -0.09846087 0.1000737 0.0517615 -0.1100145 0.1737285 0.08045529 0.009630139 0.4300331 -0.3718147 -0.1798938 0.1353817 0.222024 0.09796238 0.2418151 -0.03901261 -0.3913697 0.08073741 0.05444935 0.4214176 -0.324834 0.1785782 -0.1339334 -0.1301336 -0.1096767 0.07308844 0.1334515 -0.1123512 0.1060521 -0.1828256 -0.1485944 0.07768498 0.07514136 -0.5810335 0.2527092 -0.006459505 0.2231767 -0.5375772 0.05009216 -0.1852982 -0.6592387 0.3869758 -0.257167 0.04659801 -0.2665703 -0.688243 -0.2914381 -0.04486341 -0.341812 0.0508745 0.5511 0.04566035 -0.2074263 0.1412134 -0.5864861 -0.196584 0.0678505 -0.08136631 -0.0002990776 -0.09894036 -0.3609884 -0.1307354 -0.2054038 0.5146623 0.1809635 0.3714843 -0.04496078 0.3424926 -0.1546866 -0.05418806 -0.02919912 0.3861277 -0.6014189 -0.0201484 -0.005118703 0.2460491 0.1411195 -0.04023795 0.2587541 -0.1119136 -0.1473116 -0.1489324 0.1044895 0.2784058 -0.1150362 -0.1300242 0.187789 -0.01523028 -0.02349355 -0.1440462 -0.3129981 -0.402551 0.2546156 0.1190121 -0.1809707 0.130693 -0.02325951 -0.4906785 -0.383815 0.2333277 0.03873576 -0.170575 -0.2421352 0.2955467 0.1692119 -0.2829646 0.02867097 0.1596725 -0.0154075 0.218018 -0.1857806 0.1654191 -0.4087495 0.1327689 -0.04746816 -0.5516314 -0.2396881 0.2102901 -0.4068344 0.1741812 -0.1769497 -0.01481194 0.2139607 -0.09148802 -0.06217095 0.1975785 0.1174864 0.6523679 0.03412342 -0.1760125 -0.01639279 -0.1811818 -0.09906166 0.2118199 -0.05694224 -0.02576696 0.2097283 0.4740008 -0.7864661 0.0414389 0.08416441 0.09919307 0.2260979 -0.005455902 0.3601175 -0.226915 0.179895 -0.4302141 -0.3440962 0.1658316 -0.3868325 0.01826414 -0.5601824 0.1310656 0.1437599 0.08620407 0.1910746 0.1112856 0.02403003 -0.3978658 -0.01034274 0.01235216 -0.07199356 -0.09237787 0.4082279 -0.1904575 0.03616082 0.2700453 -0.170009 0.153965 -0.1508125 -0.1175592 0.2343417 0.1665551 -0.4508205 0.3023931 -0.2969439 -0.08154335 0.4395841 -0.2005881 -0.04913017 0.1196574 -0.3886782 0.0006816591 -0.5144338 0.1911682 0.1236269 -0.2900573 -0.08059905 0.2229048 0.2708909 0.1942507 -0.09889382 0.4522055 0.3394161 -0.09468935 -0.2850567 -0.2149266 0.1106351 -0.1573401 -0.01505471 0.06844186 -0.2921522 0.02704299 -0.1393112 0.05276303 0.3689955 0.3538993 0.2003703 0.0730775 -0.1015035 0.001761154 -0.6018007 0.1480854 -0.0948771 0.3702871 -0.0786093 -0.3744411 0.0008114871 0.1655148 -0.1095045 0.2096964 0.3186455 0.09175124 0.2620691 -0.1450124 0.09209331 -0.09349674 -1.815392 -0.6701167 0.02982032 0.0347904 0.1957529 0.4486001 -0.4723853 -0.0863952 -0.04498094 -0.001557937 0.2755344 -0.2152021 0.2745143 0.3291628 -0.4793695 -0.2726244 -0.1917552 0.2190187 -0.430105 -0.2313561 0.2524275 -0.5086857 0.05890697 0.2729525 0.1513849 0.06096247 -0.06553118 -0.07058975 0.2707134 -0.2276406 0.2066142 0.471271 -0.08985025 0.02436934 0.1594633 0.2375966 0.5846418 -0.1084522 -0.05637835 0.096793 -0.08104558 -0.3281796 0.1890666 -0.07820913 0.213546 0.2560512 -0.1501321 0.2074967 -0.1271359 0.0709004 0.1642928 0.07358306 0.2766953 0.06690075 -0.2938343 -0.3110853 -0.1968784 0.05714551 -0.3096366 0.1419662 -0.1079239 -0.4190861 0.155508 0.343 -0.6244132 0.3871004 -0.2042807 -0.4839003 -0.04170102 -0.4968863 -0.5074285 -0.1036 -0.2650582 0.07233098 0.3137369 -0.3853949 0.02574131 -0.2443348 0.4098636 -0.2946785 0.539515 -0.2569022 -0.1415741 0.007726432 -0.1116045 0.1911503 -0.5381289 0.3600619 -0.1928178 -0.3779052 -0.1809582 0.1026309 0.1527286 -0.3064575 0.1956509 -0.03492126 -0.2543257 0.112299 -0.2061979 -0.1047253 0.1323411 0.2055916 0.02091982 -0.3941631 0.2515892 -0.07952046 0.1014092 -0.06729349 0.3339875 0.02450295 -0.1407265 0.1060513 0.2626195 -0.05100311 0.3185952 0.0599499 -0.2517424 0.1572237 0.2236693 0.1817421 -0.147032 -0.01048227 -0.3283948 0.2700664 0.06529385 -0.1639972 -0.07308785 -0.123922 0.06140776 -0.0803204 -0.3214205 0.07417426 0.2260413 -0.1184636 0.257057 -0.1959625 0.2027684 0.3337653 0.0340686 0.3667836 -0.1643158 0.3377453 -0.1973436 0.04306138 -0.0572786 0.0267036 0.1745737 0.1048193 0.234716 0.2909408 0.2383694 -0.3459601 0.2360252 0.04254601 0.3022358 -0.05338687 0.2088503 -0.1481327 -0.3468107 0.2269265 -0.2437246 -0.1495838 -0.04496847 -0.4348623 -0.8762945 0.06304738 -1.057454 -0.1602689 -0.2676903 -0.09041431 -0.186235 -0.04234599 0.1203454 0.04466244 -0.02272273 -0.6968553 0.2121293 0.1761663 0.1355452 0.4165515 -0.4695696 0.1316237 0.007268516 -0.5328538 0.1199314 -0.01322949 -0.3355337 0.04620728 0.4363113 0.03559139 0.002518662 0.391669 0.006390922 0.03008011 -0.2778712 -0.08016153 0.2550333 -0.04115519 0.1600611 -0.4400146 0.02977344 0.165077 0.5772015 -0.037943 -0.3708489 0.3203363 -0.2562279 0.1943754 -0.002892195 -0.1619477 -0.2458045 -0.03311258 -0.6614746 -0.3693203 -0.1334279 0.1075695 0.3533548 0.03345142 0.03233356 0.05300591 -0.0791491 0.01141246 0.5369225 -0.1172704 0.05836625 0.3026116 0.1370542 0.1574033 0.03486321 0.2107561 0.1126791 0.01439489 -0.06204194 -0.05645039 -0.4653446 0.04476791 -0.01512476 -0.05061199 -0.3235811 -0.09511493 -0.7166732 -0.04303702 -0.1783289 -0.3912425 0.7027513 0.07262317 0.2598293 0.02948 -1.324796 0.2056488 -0.09687236 0.04716367 -0.2662942 -0.01665844 -0.309202 -0.4504624 -0.4698597 -0.3319923 -0.1482622 -0.3441535 -0.05199476 0.07429815 0.2221596 0.1751642 0.4792385 -0.1287329 -0.04480573 -0.09378625 0.06086808 -0.01992226 0.006291442 0.2747807 -0.2329406 -0.1027062 0.7117717 -0.2466797 0.03599749 0.06077363 -0.3550447 0.08975736 0.09579416 -0.01239908 -0.08087419 -0.2820302 0.3368652 0.2057997 0.2085603 0.0248155 -0.04133971 0.1705882 0.3531301 -0.2377621 -0.2631397 -0.04536731 0.03503756 -0.01801053 -0.1217433 0.1139364 -0.07637043 -0.2479727 -0.2930706 -0.5020501 0.2846231 0.0240493 0.2616299 0.1041942 0.2892725 0.01290686 0.3203202 0.3639169 0.08180411 0.01665916 0.1731816 -0.3340874 -0.1761217 -0.2963449 0.01577493 0.140913 -0.159173 0.05063028 0.08190816 -0.1239154 0.1905235 -0.0004010387 -0.1043109 -0.5115666 0.1991606 -0.2796842 0.01590766 0.09372796 0.2317923 -0.0533134 0.143594 0.08765463 0.1755194 0.007133558 0.1838076 -0.1692878 -0.1080522 -0.1236396 -0.2939207 -0.4041569 0.03413799 0.07203933 -0.1500011 0.201535 -0.586987 -0.449338 -0.3275099 0.2658792 -0.39672 0.01758795 -0.3354321 -0.1586479 0.1115271 -0.0314208 0.09232612 0.02711587 -0.07829418 0.2121414 0.1110698 0.06959114 0.4884093 0.1694279 0.08810753 -0.4962363 0.005860839 0.07404955 0.04411719 0.3925774 -0.02345839 0.1393473 -0.1263063 0.1808198 -0.1921241 -0.2946686 0.06737215 0.1622958 -0.07828214 0.1547697 0.1951434 0.007028384 0.1355887 0.07380826 -0.2006261 -1.066501 0.0059347 -0.3755323 -0.6016329 0.3346902 0.4246532 0.003750628 0.05135993 -0.3219836 0.2094749 -0.1636337 0.2227326 0.5808017 -0.06194218 -0.1788037 0.1964159 0.1780875 -0.736362 0.1014492 -0.07279257 -0.3475069 -0.1451531 0.03443777 0.1966484 -1.220025 -0.4294552 0.6899536 0.765159 0.155195 0.01268034 -0.1141962 0.3578972 -0.4209719 -0.1436516 -0.4212868 0.1369247 0.2946448 -1.034871 -0.006312883 0.09177719 -0.7600063 0.3341447 0.08623054 -0.489987 0.3617551 0.2744172 0.0885443 0.363079 -0.6304402 -0.1759735 -0.4627277 0.5879255 -0.06445925 -0.2177462 0.4909113 0.1964966 0.152262 -0.6092081 -0.4076563 -0.2332563 -0.1550005 -1.069951 -1.185279 -0.004379267 -0.2097032 -0.1054942 0.001263311 0.0791745 -1.001931 0.1888466 0.479594 0.4003314 0.2450899 -0.422125 0.1095003 0.03139836 -0.2639435 0.6404131 -0.1393095 0.0643602 0.0379663 -0.2613865 -0.01357354 0.1108737 0.3505114 0.3943172 0.225573 0.3043513 -0.08194633 0.3724633 -0.4259925 -0.9221566 -0.03452301 0.3474685 0.1818289 0.2151474 -0.03126083 0.2759993 0.06953998 -0.3555794 -0.3008941 -0.2032086 0.216344 0.204326 -0.009985601 0.0707041 0.1303046 -0.25301 -0.3685126 0.09576398 -0.2617554 0.1692303 -0.0658019 0.2475561 0.01367728 -0.4042204 0.2683191 -0.2194164 -1.453501 0.5247794 0.07003192 -0.05643559 -0.1838265 0.08212896 -0.02249279 -0.5745695 0.1710698 0.2087554 -0.5679974 0.1281995 -0.3878454 -0.04786949 0.04288126 0.00948143 -0.2358881 0.2911723 -0.2254557 0.04350419 0.3368597 0.2162668 -1.154274 -0.8831565 -0.008976903 -0.1450561 -0.2942637 0.537114 -0.4852544 0.1859277 0.2230736 -0.1100303 0.1549712 0.3473688 -0.01558281 -0.0061812 0.3838534 -0.06160665 0.04827671 0.09338473 -0.03995052 0.05812016 -0.04294428 -0.1660526 0.1502814 -0.4214727 -0.1840693 -0.008248913 -0.0131814 0.3575761 -0.2854693 0.1882848 0.1448089 0.398501 -0.2060799 -0.03937121 0.1543563 0.2362895 -0.3818633 0.04894479 -0.004328771 0.03728459 0.1861034 -0.1066651 -0.2663297 0.1683964 -0.05328418 -0.08346932 -0.1275589 -0.05274243 0.2170099 0.1344925 0.2415772 -0.2186313 -0.4096072 -0.2062504 -0.3026992 0.4836559 0.3034492 -0.1526831 0.2208134 0.1068653 0.3497293 0.2156428 0.02062577 -0.2685173 0.1328016 -0.1897278 0.1883336 0.1430128 0.1228996 0.1100717 0.2563125 -0.1864391 -0.1293505 -0.01251442 0.1822366 0.04575179 -0.1669026 -0.2278189 -0.1012584 0.3523556 -0.07698304 -0.114622 0.3798308 0.3752064 0.06467879 0.01907092 0.1897679 0.3203187 -0.100529 -0.2730164 0.0460516 -0.04526782 0.1369459 -0.4540279 -0.4680999 0.2925996 0.1843852 -0.2253564 0.1585502 -0.03265447 0.357046 0.1441923 -0.4421272 0.01687203 0.08325324 -0.3351977 -0.07325968 -0.226089 0.009268201 -0.4966686 0.06985099 -0.2665857 0.002205535 -0.3779411 -0.1964283 0.1167074 -0.2037604 0.1453548 -0.1812911 0.3138736 0.6449436 0.2377201 0.02291431 -0.3666171 0.07526156 0.09642823 0.4196746 -0.5776892 0.2095722 -0.3928013 0.1774898 -0.2220288 -0.1787245 -0.5976848 0.4082788 -0.1174128 0.2913214 -0.3462109 0.08148545 -0.01903067 0.06740807 -0.2335671 0.1536831 0.2850414 0.3820875 0.2744652 0.4946454 0.1647058 -0.4768065 0.001938165 0.2379729 1.010734 -0.3350555 0.1105023 -0.0104243 0.6621879 -0.1148759 0.3927455 0.02476679 -0.2832209 -0.2663971 -0.1729363 -0.003061227 -0.08226023 -0.1400386 -0.3302724 -0.06057371 -0.2640773 -0.1953669 0.08069975 0.7749964 0.222616 0.3377191 -0.05836033 -0.2769976 -0.3042312 -0.2767796 -0.3040748 -0.2491908 -0.1507361 0.2760221 0.2081936 -0.0222805 -0.4384621 -0.4030932 0.2930226 -0.495453 -0.009670701 0.4292272 -0.1316934 -0.08063742 -0.3240827 -0.3857984 0.4243479 -0.127858 0.2584804 -0.4028115 -0.09423284 0.0678665 -0.1250863 -0.2490378 -0.3023711 0.4191133 -0.5311943 0.368409 -0.1236761 0.01589077 0.4655243 -0.09967711 -0.08817001 0.2622822 0.04964832 -0.1246171 0.09232713 -0.37294 0.3758774 -0.196076 -0.06395245 -0.09266627 -0.2114543 -0.2262166 -0.1953885 -0.1154169 0.1081172 -0.167325 0.09631293 0.04895041 -0.04944714 -0.1857504 0.1671663 -0.06631326 0.765855 -0.001181613 -0.2928397 0.4261453 0.2088926 -0.2744807 -0.06278191 -0.04340594 -0.3186618 -0.1848628 0.01857564 -0.1985673 -0.1797154 0.63455 -0.05220363 -0.08056013 0.1312437 -0.1472854 -0.1285312 -0.001977746 -0.08452472 0.01483428 0.1841142 -0.12391 -0.2300609 0.08388877 0.2946486 0.1455848 -0.3504442 -0.08399576 0.0502757 0.04001344 -0.1025871 0.6182353 0.03150036 -0.09029863 -0.1840514 -0.03805134 -0.1883077 0.0821816 0.01194216 0.2900992 0.1923569 0.04600925 0.004981054 0.06503055 0.08738514 0.5427194 -1.747684 0.1899671 0.5120082 -0.09283216 0.01906507 -0.2246248 0.1029787 -0.06057951 0.2730487 -0.2447312 -0.0942463 0.2223309 0.1581887 -0.04804058 -0.1685836 -0.08887265 0.02022613 -0.1770196 0.1827851 0.1309048 0.3600571 0.06131596 -0.2234965 0.4026653 -0.1596242 0.1211521 0.2416054 0.2180078 -0.7645838 0.4689979 -0.2730019 0.1816668 -0.06714301 0.2359047 -0.3425983 0.1111254 -0.710321 0.3828628 -0.07094311 -0.2311699 -0.4880695 -0.03759044 0.2990881 -0.1801057 -0.3554881 0.09994232 0.2855309 0.08530327 -0.1540884 -0.1306322 -0.4422672 -0.1943262 0.1560111 0.1056093 0.2602069 -0.003689183 0.4254995 0.4239976 -0.191289 -0.3564584 0.3895272 0.2819226 0.4034719 -0.3088416 -0.4916989 -0.175475 -0.6710052 -0.1979514 0.4721939 -0.121017 -0.2878093 -0.05900143 -0.13276 0.1723043 0.07280928 -0.08040986 0.1790048 0.156016 -0.1399571 0.04518698 0.2645285 -0.03461494 -0.04636482 0.1020438 0.6465304 0.06007029 -0.05020754 -0.1713536 0.07068233 -0.3744253 0.5062395 0.07339051 -0.1957046 0.3904262 -0.3876173 0.3668744 -0.2834518 0.2830694 0.2042714 -0.8140978 -0.2929735 0.06428128 -0.01292738 0.1101708 -0.3955357 -0.1906374 0.220303 0.1797305 0.1421805 0.1930117 -0.1262251 -0.3544147 -0.1133452 0.2181202 -0.2224556 -0.0819956 -0.2681283 -0.2155129 -0.438497 0.0724785 0.08572195 0.4038963 -0.138889 0.2158997 0.07376867 -0.02415392 -0.05466977 0.11218 -0.6407988 0.08527699 -0.3127505 0.03398754 -0.06385556 -0.05292515 0.3273134 -0.1488268 0.07603042 0.02264906 0.04995226 0.1285226 -0.264608 0.111181 0.1770333 -0.2007441 -0.206703 -0.2197841 0.1627216 -0.1281185 0.0313765 0.2673553 -0.2490015 0.2455771 -0.4568076 -0.2605143 0.2370539 -0.4353722 0.2675933 -0.8496038 -0.09077283 -0.09899188 -0.01720427 0.2619364 0.3192351 -0.04899947 -0.6285517 0.222072 -0.044011 -0.1737054 -0.09920088 0.4711906 0.3661352 -0.01298187 0.1243886 0.249029 -0.4509165 0.4342313 0.3896302 -0.3267424 0.09215095 -0.2526275 0.0720525 -0.122762 -0.5914183 0.02724056 0.1511546 -0.246469 0.3391724 0.06160555 -0.01254559 -0.02393244 0.4015798 0.1135503 0.1938952 -0.1385832 0.2242481 0.06975446 0.1090408 -0.06366071 -0.07911851 -0.1616917 0.07429281 -0.4531733 -0.3836059 -0.02037954 0.1838605 0.2966863 0.2924695 0.04957373 0.03410208 0.04475234 -0.1180025 -0.2660161 -0.1810377 -0.06658838 -0.3018503 0.195316 -0.5488862 -0.2757452 0.16382 0.2793 -0.1608364 -0.1894029 0.1601331 -0.5617472 0.164669 0.2915947 0.2998035 -0.319815 0.06968909 0.1462542 -0.2515566 0.2991869 0.3121701 0.19649 -0.09782817 0.09926616 0.02142405 0.2674654 -0.4456894 0.3425698 0.4281177 -0.4674942 -0.3034345 0.1279505 -0.02736095 0.1678022 0.1186598 -0.098131 -0.06251503 0.05593352 0.4582169 -0.1849254 -0.01796193 -0.1353931 -0.2806558 -0.05419038 0.3494695 0.1037846 -1.304895 0.3563728 -0.7414315 -0.09671795 0.3519751 0.4607495 -0.1128865 -0.06723491 0.05949834 0.2271119 -0.6450621 -0.06305952 -0.3619196 -0.1017047 0.9987802 0.07471266 0.07409054 0.09543597 0.1483069 -0.1881009 -0.2584152 -0.1035957 -0.1436811 0.02812486 0.07968207 -0.3466353 -0.2575241 0.3770912 0.2494812 0.04926884 -0.1852879 0.1515559 0.02976604 -0.06387404 -0.03651846 -0.004418586 0.06661 -0.1794122 -0.09016489 -0.1041815 0.008681222 -0.178018 -0.2200795 0.09282056 -0.1933105 -0.05496182 0.1434585 0.1723278 0.1542761 -0.1655763 0.1673751 -0.2918237 -0.1015045 0.1466001 0.6098979 -0.507353 -0.2208849 -0.1915419 0.01307159 0.1884741 0.09045321 0.2520665 -0.318719 0.2881713 -0.5578968 0.002820129 -0.1360114 -0.1667435 -0.05470919 0.1962922 0.1976823 -0.02609418 0.4104598 0.2934236 0.02204236 -0.03615276 0.1816075 -0.2806419 0.007087109 0.4120318 0.009063536 0.08714022 -0.1595859 -0.01430881 0.2070246 0.1745332 -0.3682279 0.0437202 -0.08159472 0.4487491 -0.2521504 0.1209777 0.03077197 -1.03794 -0.03314658 -0.2314622 -0.1087278 0.2485805 0.3228744 -0.09662401 -0.1058022 -0.02285487 -0.05862332 -0.1519515 -0.1137526 0.1944356 0.1250583 -0.02511295 -0.3253918 0.3807494 0.1909139 0.2316485 0.1127353 -0.2953859 0.1990614 -0.008264964 -0.006917719 0.06286549 -0.2372049 0.2408294 0.08474515 0.3523656 -0.1090779 0.1150993 0.08868743 0.401156 0.02864094 0.07971991 0.3801324 0.3562741 -0.4040617 -0.6442375 -0.2585746 0.1281724 -0.2083555 0.1034978 0.05475693 0.3600105 -0.2722147 -0.1649805 -0.2415301 0.08545905 -1.040964 0.02267422 0.06322979 0.03091464 -0.2102311 0.02417772 0.4654894 -0.08120591 0.06661949 -0.477912 0.2528696 -0.07758092 0.2731647 -0.09367842 0.1931896 -0.2334695 0.1951561 0.1893836 -0.2739975 0.1100372 0.3313305 0.2002675 0.442921 -0.3798466 -0.02931669 0.4438562 0.2441085 -0.3372868 -0.07545841 0.05560854 -0.08767927 0.2648145 -0.231796 0.09637965 -0.1864207 -0.2345667 0.3288037 -0.02137554 0.1012634 -0.2908671 -0.02413714 0.08278188 -0.1520217 0.08049128 -0.09491897 0.2927496 -0.07382406 -0.1293415 -0.5585121 0.0716248 -0.5352958 0.09671841 -0.46167 -0.1225635 -0.2969562 -0.3535731 -0.2518847 0.04683698 -0.1431047 0.07130643 -0.3747676 -0.1979947 -0.2701989 -0.004245622 -0.337487 0.1584735 -0.02604777 -0.1941619 0.01223434 0.2758148 0.1362347 0.09255654 0.09799728 -0.5728755 -0.2984229 0.1041065 -0.07858065 -0.02402989 0.1685999 0.08933649 -0.3402289 0.628101 0.1389699 -0.2262215 -0.01272107 -0.443446 0.3519943 0.06802165 0.0278964 -0.2618177 -0.2582507 0.3122143 -0.1257666 0.3622589 -0.1773279 0.04312292 -0.2520828 0.04157783 -0.01276365 -0.05963569 -0.03599397 0.01149879 -0.3299941 0.11477 0.5182203 -0.1100474 0.2215132 0.1876964 -0.04539569 -0.2440598 0.1716132 0.2241786 0.5544484 -0.03080742 -0.007652564 0.09858999 -0.09935818 0.08497957 0.1580208 -0.02784217 0.2363501 0.1736373 0.02356134 0.247728 -0.0630666 -0.07914758 0.07297893 -0.2480312 0.3613452 -0.1676566 -0.07069008 0.4713682 -0.2994709 0.1493809 -0.5883273 0.203384 0.07273849 0.02539528 0.06630859 -0.008869985 -0.6483962 -0.03384255 0.08881625 -1.064077 0.08043907 0.05675578 0.1146602 0.1125014 -0.4547426 0.2711223 -0.3808366 0.09835846 -0.1479893 0.02945187 0.1541316 -0.03022325 -0.3161973 -0.05592117 -0.1928217 0.4639383 0.2220207 -0.1209893 -0.3002078 -0.08408614 -0.2688529 -0.02377835 -0.3210902 0.01043543 0.3906964 -0.02268353 -0.04052966 0.424752 0.2208048 -0.04105199 0.03309672 0.4196222 -2.131592 -0.0191471 -0.08824379 0.05568376 -0.101504 0.06422416 0.05319598 -0.2649304 0.007953973 0.07811641 -0.09096717 0.08872063 -0.3189301 -0.2164305 0.5093299 -0.1171676 0.001530413 -0.2296308 0.1564945 0.2738394 -0.3090683 0.2832689 -0.3489425 0.3398924 -0.2981159 -0.2566359 -0.1008647 0.3286863 -0.1236284 0.06142983 0.3385787 0.1751996 -0.07072945 -0.1257535 0.1387388 0.09593811 -0.002127867 0.3527118 -0.1732544 -0.3240693 0.4518674 -0.1256371 0.05865593 -0.01634404 -0.587308 0.04887364 0.3024045 -0.1160103 0.01786216 0.05988465 -0.0660813 0.1186681 -0.05414557 0.2444914 -0.3086804 0.01946333 -0.04814739 -0.1010677 0.2207542 0.06884542 -0.128133 0.2923595 0.02724261 -0.0673559 0.1465512 0.08867679 -0.2091345 0.4568694 0.1648235 -0.1030755 0.1170314 -0.1951283 0.3469754 -0.04909714 0.3294363 0.2491031 -0.04437181 0.02877812 0.2899508 -0.204001 0.09376827 0.1003175 0.2338258 0.2752272 0.01971043 0.166051 -0.2022937 0.3179636 -0.3102935 0.06914561 -0.1280548 0.06467414 -0.4349956 -0.1958174 0.2571802 0.08782179 -0.1608482 -0.05383034 0.03145109 -0.05533186 0.1345176 -0.01375743 0.06609633 0.04362719 -0.01534073 0.06373695 0.05270228 0.104007 -0.1146981 -0.0152238 0.1975351 0.3164556 -0.2056708 -0.1159085 -0.1103452 -0.3365526 0.1095256 0.2172505 -0.03162035 -0.2101037 -0.07582911 0.0600135 -0.08173711 0.3629441 0.03359746 0.07202819 0.07603025 0.5150803 -0.1929887 -0.1576932 -0.6900367 0.4647938 -0.1440631 -0.07825447 -0.07242149 -0.2189666 0.2755757 0.03835068 0.01434022 -0.02639602 -0.09415138 -0.2154541 0.2735599 -0.03978449 0.09021875 0.3993118 0.280795 0.2529084 0.1796363 0.1767772 -0.2603712 0.2982829 0.1592537 -0.3134164 -0.007028861 0.03232883 -0.4488059 0.2058699 0.04245477 -0.1182 -0.09437011 0.1694735 -0.2182116 -0.1917667 0.01230919 0.08361651 0.105804 0.4053682 0.252786 0.348188 0.2003349 0.3923539 -0.2722969 0.1115037 0.03383958 -0.04708378 0.2506469 -0.3163368 0.2111732 0.07543486 -0.2738537 0.06050413 -0.33552 -0.05767885 0.3955736 0.1257044 0.09837251 0.2201862 -0.1484874 -0.03050637 -0.01049953 0.1711907 0.252398 -0.08185302 -0.3608854 -0.1307748 0.2666159 0.2888629 0.2163231 -0.3041009 0.1460125 -0.204868 -0.1699805 0.03350065 -0.2681349 0.2520239 0.2320411 0.1170572 -0.07567659 -0.2605233 0.03946656 -0.01726771 0.2881844 -0.08266357 -0.4905838 0.04488105 -0.3393249 0.06573942 -0.1119305 0.052425 -0.6121304 -0.3322034 -0.1113199 0.2341668 0.06808386 0.4361144 0.3372881 -0.1042168 -0.99159 0.1434217 -0.02986388 -0.0598937 -0.2752852 0.353043 0.136206 -0.3702189 0.3758082 0.3485966 -0.4757214 0.4697546 0.06935469 -0.4254742 0.2737613 -0.4163103 0.2857757 0.04571263 0.1640487 -0.2929691 0.0295293 -0.3521076 0.1361887 -0.04392479 0.3597199 0.200474 -0.2861251 -0.3355689 -0.3614582 0.10709 -1.242956 -0.02472173 -0.3317518 -0.3122783 0.4222971 -0.283012 -0.2547249 -0.2269313 0.2701946 0.01800951 0.1247581 -0.2505394 -0.1675962 -0.04659387 -0.4720014 0.259447 0.2513363 -0.409632 -0.2869131 -0.128122 0.2684936 -0.04179563 0.2051017 0.04169403 -0.03804618 0.3775729 -0.4341431 0.4009526 -0.3143423 -0.3625557 0.1191287 0.3998643 -0.2670784 -0.1290522 0.5032494 -0.08813261 -0.1017111 0.4545225 -0.08665979 -0.6812736 -0.2010364 0.298589 0.08541901 -0.3625259 0.2021693 -0.3185436 -0.2448689 0.4536174 0.1168675 -0.06965613 0.08581588 0.02647856 -0.2065012 0.09657835 0.003704337 0.04619227 -0.1205202 0.1394983 0.2029524 0.2085178 -0.4110308 0.1977882 -0.4146265 0.06773961 -0.2142721 -0.3212304 -0.1233545 -0.240473 0.02881229 -0.1546967 0.135401 -0.004485439 0.04248639 0.1675815 -0.5017781 -0.1935015 0.1696263 -0.3133364 -0.2404668 -0.07405846 0.1554567 0.2783468 0.0990419 0.2436283 -0.05950021 -0.001685659 -0.2151647 0.4694915 0.1721444 -0.08539524 -0.5584735 -0.7653927 -0.1733385 -0.4759473 -0.1512645 0.4872702 0.3107359 -0.2368471 -0.581918 0.1378139 -0.1514376 -0.1099762 0.1334398 0.3395771 0.1928627 -0.001505888 0.1311786 0.04511317 -0.5566099 0.4515538 0.1863892 -0.0051106 -0.09712512 0.3556026 -0.005969761 -0.04482801 -0.6072956 -0.3242117 0.4766639 0.3950015 0.1696072 0.06890117 0.009912285 -0.2611414 0.03204358 -0.1694914 0.5925542 -0.1300642 -0.05198694 -0.1544159 0.6161264 -0.2592255 -0.08202298 0.3048249 -0.09438537 -0.07038769 -0.03971721 -0.08709238 0.1407394 0.01130769 -0.287234 -0.2258127 -0.5090119 -0.09708163 -0.270008 0.1715305 0.5255513 0.1300608 -0.1189618 -0.2382531 -0.5963339 0.1354373 -0.3278517 -0.02664256 0.5683723 0.08544231 -0.06954311 -0.009111772 -0.08480052 -0.3370385 0.1343586 0.3544606 0.02777902 0.2211388 0.01936817 0.3453403 0.2829288 0.186263 0.447506 -0.1559132 -0.207978 -0.0576937 -0.0993253 0.2121312 0.5513646 -0.1495993 -0.1334969 0.1462765 0.2643149 -0.0690519 0.3827313 -0.1641017 -0.4898285 -0.3224855 -0.04493146 -0.06127651 0.2332633 0.1316189 -0.4833994 -0.08348773 -0.1057581 -0.4341368 -0.4048823 0.1090176 -0.2515831 -0.2339921 0.2588619 -0.05215722 0.05569348 -0.1914338 0.2742983 -0.1465845 -0.112023 -0.237475 -0.3429678 -0.002110383 0.0899043 0.461225 -0.01913276 0.1979484 -0.4546572 -0.3686664 0.3809871 -0.3350477 0.4094902 -0.3956674 -0.333688 0.02106796 -0.03380755 0.2865866 -0.01299731 0.1180867 -0.06657485 -2.067889 -0.2468165 0.2076831 -0.08033314 -0.2191672 0.3230035 0.2568506 -0.09521325 -0.4543934 0.1588178 0.1387249 0.1148713 -0.652852 -0.01570756 -0.777629 -0.1487755 0.1026247 0.2257734 0.04689625 0.01780598 -0.6635103 -0.2471367 -0.1165601 0.4982764 0.4015028 0.4043773 0.04394474 0.003413286 -0.2051199 -0.02093433 -0.2002317 0.4682346 0.1804517 0.3732116 -0.03866328 0.5849685 -0.1846821 0.07406081 -0.4391352 -0.0589201 0.2040223 0.3073153 0.2190944 0.2530556 0.07147558 -0.1857356 -0.02577714 0.04281365 -0.07215787 -0.2863959 0.08186299 0.2210951 -0.1709433 -0.1377385 -0.4148027 -0.06680722 0.2243794 0.01313666 -0.2688675 -0.04976662 -0.2461115 -0.05479743 0.4415192 0.1978001 0.07130722 0.3620558 0.201076 0.3228283 -0.01750324 0.101757 0.20063 0.5104443 -0.2656376 -0.1745266 0.03911844 -0.09276496 0.1028483 -0.3431446 -0.111782 0.3459841 0.00992622 0.2382038 -0.4435413 0.1298184 0.1292449 0.3576513 -0.3686848 -0.8760964 -0.1633566 -0.1292155 -0.1901133 -0.3889605 0.2941678 0.02505604 -0.04766112 0.1138735 -0.1640093 0.138157 -0.04659296 0.09461559 -0.08375771 -0.3693772 -0.1124178 -0.1911516 0.02317659 0.05310012 0.1114644 -0.2674252 -0.07849758 -0.3213031 0.3100353 -0.6067293 0.268748 0.1279389 -0.5277852 0.1107087 -0.1938143 0.1636204 0.1714754 0.2657671 0.2393196 -0.1079069 -0.2051252 0.1056241 0.3976506 -0.3573129 0.3501391 0.03427601 -0.566249 -0.3576115 -0.102741 -0.08578006 -0.2442674 0.08611259 0.3696077 -0.382677 -0.3306324 0.2684388 -0.2093322 -0.1403218 -0.1703099 -0.1846567 -0.4299694 -0.06528596 0.3065995 0.0774746 -0.1731519 -0.06214134 -0.2098296 -0.1993111 0.2713125 0.3831505 -0.04262191 -0.00796863 0.2156012 0.05917921 -0.1769914 -0.1895951 -0.1326696 -1.021466 0.2017265 -0.2638177 -0.1537548 -0.05137512 -0.1301649 -0.2685908 -0.1585001 -0.2971486 -0.2335226 -0.04681129 0.5374894 0.1826847 0.1781864 0.1926634 0.1721747 -0.05601151 -0.2692632 0.1373662 -0.2833953 0.1238339 0.2379529 0.02212065 -0.03935322 -0.0715131 0.1214893 0.1408891 0.01000541 0.03692699 -0.1663825 0.137863 -0.3851576 -0.1570893 -0.2327433 0.1282093 -0.02754877 0.2550095 0.08637873 0.1731711 -0.1499801 -0.1299482 0.1114823 -0.05701438 0.380044 -0.008769389 -0.120891 0.1258886 0.1092426 0.2033546 -0.2394018 0.06345587 -0.3713209 0.07509854 0.1276893 0.1964901 0.3482467 -0.03852829 -0.2752034 -0.2074883 0.02532904 0.1589166 -0.008511202 -0.1724255 0.06385539 -0.08324435 -0.03646628 -0.3815778 -0.0578253 -0.3662503 -0.1385382 0.1468568 -0.04888022 0.1088059 -0.1474556 0.3627758 0.1212351 0.04399859 -0.2662744 -0.4170575 -0.02318375 -1.329437 -0.2103792 0.2284106 0.5043235 -0.04520011 0.3209251 0.1276189 -0.3282878 -0.1434319 0.2687113 0.09844891 0.01082037 -0.04052351 -0.07868809 -0.02233027 -0.383653 0.1380523 0.6763818 -0.2747087 0.09485905 -0.001589434 0.1046497 0.00681941 -0.02213226 0.2299173 0.3059704 0.3802418 -0.03541404 -0.2549114 -0.1375982 0.005440456 -0.09791675 0.09341344 0.3871871 0.08663929 -0.1036438 0.2734349 -0.2370719 -0.07034746 -0.2473998 0.2429339 0.04800538 0.4127995 -0.1918906 -0.01550424 0.2736078 -0.3305514 0.1303296 -0.5091503 0.09492362 -0.4568115 0.09490757 0.09300247 -0.1813647 -0.1866072 0.2876222 -0.4639523 0.0788614 0.02203017 0.2369363 0.05318454 -0.3088071 -0.03712483 -0.2940188 0.5176288 0.03073499 0.1838543 -0.1254459 -0.4388641 0.1598205 0.1351759 0.07511317 -0.2181583 -0.1063056 -0.3372825 0.1847393 0.1186401 0.02402292 -0.04697173 -0.4951708 -0.04574107 0.0602718 -0.3378479 0.009071091 0.6715697 0.0187893 -0.06474875 0.07085116 0.2155901 -0.4066272 -0.3273248 0.06492774 -0.07053538 -0.2161733 0.6287984 -0.6960575 -0.4560418 -0.003447336 -0.2394736 -0.3688857 -0.05304587 -0.08555859 0.05164517 -0.2545629 -0.05205136 0.1498017 -0.1054014 0.3977992 0.1280699 -0.07051953 0.3047071 0.2811099 -0.122952 -0.2671838 -0.468666 0.2474656 -0.3515891 0.2407472 -0.1148778 -0.1859359 0.04029512 -0.431015 0.04644484 0.308926 -0.2474574 -0.02227161 0.2459776 -0.1186928 -0.1929217 0.3123376 0.2673526 -0.1845249 -0.3199615 -0.1385995 0.2033992 0.2665326 0.2086808 -0.3278566 0.0790382 -0.03897404 -0.6549342 -0.2112209 0.5127815 -0.08544029 0.09724511 -0.2062145 -0.02959191 -0.3014087 -0.03273262 -0.08560082 0.05734238 0.264535 -0.06626282 0.4681468 -0.214884 0.4001134 0.1991791 0.1970275 0.09153495 0.06800989 -0.1136473 0.1273389 0.3459874 -0.2038586 0.3810836 0.05662167 0.06208934 -0.181528 -0.08178384 0.03466109 0.5440546 -0.2090559 0.004777574 0.2704176 0.03970681 -0.5446727 0.3913785 -0.1578042 0.2946068 0.2953267 -0.2513202 0.127594 -0.5513183 -0.0609665 -0.1915448 0.2919822 0.1770907 -0.3526786 -0.1194004 0.04174248 0.1096398 0.09013995 -0.1024846 0.01152196 -0.1152859 -0.6878506 0.05618413 -0.1808822 0.189815 -0.2457088 -0.09377507 0.6167325 -0.2551125 -0.355151 -0.01686388 -0.2753067 -0.1835976 -0.06609057 0.0437404 -0.9626885 -0.2673344 0.09124951 -0.13818 -0.2653547 -0.4797727 0.3196047 -0.144389 0.1905691 -0.7250856 -0.1146671 -0.01952653 0.1063826 -0.2902708 -0.06218485 0.1451791 0.1475889 0.3228019 -0.1617192 0.3165194 -0.1713621 -0.003109815 0.5475884 -0.06372822 0.07713499 -0.1256005 0.2495815 0.07938251 0.2253732 -0.2600097 -0.1230975 -0.5474979 -0.004202627 -0.2734025 -0.1985903 -0.1669471 -0.1654469 0.04707418 -0.03549271 -0.2285299 0.3283144 -0.1357495 0.08356355 -0.4178033 -0.2207788 -0.1529148 -0.09265124 -0.6570209 -0.05402413 0.2883016 -0.3048079 -0.004813399 0.2609703 -0.01975405 -0.2255436 -0.07608543 -0.2256609 0.09065087 0.349998 -0.1207994 0.3104926 0.01360594 -0.2770479 -0.2193108 0.5065832 -0.218034 0.03665177 0.5286262 -0.1209315 -0.2334503 -0.03136806 0.1571867 -0.458029 -0.1990862 0.5515617 -0.01309329 0.02653267 0.3933566 -0.2874612 0.2891026 0.2529626 0.08293185 0.2540341 -0.630533 0.1599506 0.1873474 -0.4813576 -0.01802229 -0.03125806 0.4985376 -0.3729746 0.05027762 0.00190763 0.05892127 0.2644237 0.1475493 -0.5780752 0.2585522 0.2284818 0.4291004 0.04712479 0.6285215 0.5244809 -0.472634 0.1446184 -0.3221981 -0.6790424 0.01599258 0.04434907 -0.04601733 0.09148679 -0.3271872 0.1291754 -0.2610266 -0.7474662 0.6750828 0.3067774 -0.1919018 -0.002440063 -0.006246673 -0.1274413 -0.5480978 0.3143981 -0.1243634 -0.408735 -0.2104448 0.04496395 -0.4305333 -0.1082506 -0.02547548 -0.3998352 -0.05310473 -0.02699308 0.3948809 0.08449923 0.1261761 -0.08235562 -0.5692134 -0.2048937 0.7006937 -0.3237992 -0.08333421 0.2390399 -0.6969404 -0.1140718 -0.1451439 -0.09574265 0.176977 -0.3161166 -0.0727916 0.3707981 0.1760382 -0.114924 -0.2483501 0.3793539 0.1680263 -0.000701979 -0.1236827 -0.1420656 0.1462988 -0.1043439 -0.7644826 -0.1184333 -0.3390396 -0.2877322 0.08502667 0.0389273 0.1989658 0.2793341 -0.0524205 -0.197939 -0.3959988 -0.3647012 -0.2435656 -0.2351038 -1.083496 0.0690367 0.1694981 -0.09161286 0.4875656 0.2048736 0.164917 -0.1245373 0.1097744 0.2012813 0.18234 0.3731305 -0.04923701 0.4294709 -0.03653792 0.07506639 0.04053727 0.482575 -0.505899 0.4900813 0.3808261 -0.004378409 0.1231797 -0.4970298 0.02526625 -0.02462935 0.3959354 -0.1529071 0.1596824 -0.1436292 -0.2988026 -0.207191 -0.07187099 0.3745052 0.1018372 -0.4636795 0.2273434 0.2071625 0.05931211 -0.4785757 0.110301 -0.04687286 0.5080866 -0.2412074 0.1351895 0.006435423 0.03848018 0.2931446 0.1053531 0.1894209 -0.2126247 0.0180386 0.8281282 -0.3446469 -0.2771093 0.2836405 0.2495225 0.2663109 0.2985792 0.3688611 -0.09302478 -0.4250733 -0.2127065 -0.1236351 0.400625 0.07831776 -0.1545638 0.2292262 0.7005864 -0.09582501 0.1533381 0.3093893 -0.05296906 -0.1894262 -0.03923699 0.1135743 -0.4590752 0.09271413 -0.01502336 -1.121119 0.1145387 0.07531817 -0.03633631 -0.1859053 0.2229296 0.0938729 0.03445331 0.004305926 -0.01839975 -0.1315327 0.1245288 0.08462994 0.1769529 -0.3066364 0.1633969 -0.04010484 0.2920925 0.1213371 -0.1817512 0.2313336 0.3147951 -0.1152286 0.3476549 -0.07419833 -0.1903994 -0.2650861 0.2139969 0.1342443 0.05327027 0.1152378 -0.3869467 -0.3422164 -0.2708252 -0.1831946 -0.07707271 -0.05120694 0.1649344 -0.08670813 0.2104074 0.2573068 -0.04125726 -0.1312129 0.2795076 0.4034054 -0.1672388 -0.1530866 -0.3866495 -0.1774635 -0.1214666 0.3147585 0.001376664 -0.3301798 -0.1458664 0.1662658 0.0832231 -0.154705 -0.001000187 0.2101065 -0.3702153 0.4660524 -0.2597129 0.05702972 0.01730529 -0.3517204 -0.1779037 0.4055617 -0.4712116 0.1749828 0.2071646 0.02706156 0.2676318 -0.072956 0.241827 -0.4628147 0.02790768 0.2298663 0.07303798 -0.2315044 0.3582568 -0.4754245 -0.2050533 0.3900129 -0.09641297 -0.2172191 0.3220313 -0.4474537 0.1923706 0.1967231 -0.02986721 -0.184624 0.3507697 0.1242674 0.3174161 0.08322501 -1.004105 0.09121176 -0.05748264 0.1836297 -0.03087296 -0.1253969 -0.7018415 -0.1946579 0.03883513 -0.05986456 -0.1151681 -0.1023059 0.002420948 0.176205 0.2199268 -0.3552052 -0.5187245 -0.01756245 0.235541 0.2118663 -0.1617695 0.1906338 -0.3158866 0.07695918 0.2639008 -0.09147628 -0.01543415 0.05186133 0.215345 -0.1731322 0.3070552 0.3459249 -0.07076969 -0.7380644 0.01464857 0.03187246 -0.1246711 0.2561732 0.2038316 0.2467123 -0.0843004 0.1850431 0.0592992 0.04453478 -0.08589824 -0.189651 0.3711931 -0.2081862 0.6683242 0.1849847 -0.1187736 0.2789234 0.1369999 0.1205019 0.03946149 0.03039821 0.5031471 0.4693451 -0.8226713 -0.3992013 0.3640076 -0.03221038 0.02850275 0.07912805 -0.3464999 -0.6271909 -0.1699232 -0.3029488 -0.5020075 0.03989691 0.1167315 0.01189042 -0.398608 -0.1705427 -0.5160744 -0.3738967 -0.06933999 -0.2854299 -0.4613267 -0.4176706 0.1750401 0.08432587 0.018197 0.1398567 -0.1222139 -0.9200141 -0.2881373 0.2813486 0.3107568 -0.03256206 0.5207339 -0.2403544 -0.008086652 0.3406012 -0.2962952 0.251401 0.2100926 -0.4076518 -0.05272965 -0.1150572 0.1145807 -0.1235298 0.4800404 -0.2180604 0.1342394 0.02839243 0.2915185 -0.2270269 -0.8480586 0.2590357 -0.05798901 -0.5102268 0.7148421 -0.4050778 -0.4552129 0.2629702 -0.192042 0.1412343 -0.1502213 0.03516128 0.1828261 0.1286903 -0.2039361 -0.1821649 -0.07899231 -0.2478026 -0.1384144 0.1413822 -0.2222528 0.04736655 -0.09475544 -0.3865049 -0.1578544 0.1442487 -0.05790328 0.04423869 0.08065851 -0.2163448 -0.2157656 0.1168275 0.005510913 -0.03960631 0.03552215 0.04205938 -0.00913101 -0.06396427 0.02057687 -0.1484655 -0.3860995 -0.3584379 -0.1063052 -0.04400817 0.117603 0.1192759 -0.3545144 0.2272418 0.1550353 0.0276485 -0.3384082 0.1771779 0.2092501 0.2956286 0.1529513 0.1228109 -0.004878355 -0.2254451 0.02232689 0.1086552 -0.02004318 0.08250499 0.07126231 0.03647563 0.2841403 -0.0870853 0.1165644 -0.1463192 -0.03840532 -0.3588663 0.06132475 -0.01314654 -0.06615296 0.151439 -1.365533 -0.2117295 0.1718181 0.1941613 -0.3526454 -0.3167597 -0.2796716 -0.2365099 -0.2659138 0.07424074 0.3540294 -0.3291057 -0.2484039 -0.4558284 -0.2760516 0.1821396 0.1130173 0.1448825 0.1413984 -0.1914292 -0.4636905 0.08436107 0.1311514 0.4884921 0.1670694 -0.04145806 -1.220101 -0.2144774 0.08309979 -0.1750971 -0.4647173 -0.1990131 -0.2854249 -0.147662 0.2548889 -0.2279051 -0.2325241 0.1644907 0.2242298 -0.03792134 -0.2652248 0.2322043 0.08857981 0.1451358 0.2347696 0.1639047 0.2535688 -0.2955208 0.231284 -0.2160015 0.211082 0.08302561 0.1793958 0.3200419 0.170027 0.07301017 0.2425682 -0.3836967 0.1730743 -0.1407584 0.189364 -0.1332975 -0.2049304 0.135016 -1.422371 -0.193086 0.1555201 0.1865548 -0.3642235 -0.3527572 -0.2867853 -0.2466519 -0.2623367 0.08736728 0.3674631 -0.3337288 -0.2603423 -0.4593128 -0.284735 0.1834695 0.1261843 0.1235739 0.1380923 -0.195412 -0.4812217 0.06545865 0.1740481 0.4368807 0.1532652 -0.07608095 -1.359936 -0.1900354 0.08079438 -0.1756639 -0.3630717 -0.2078722 -0.2895911 -0.1568552 0.2774935 -0.2093901 -0.2309227 0.1606933 0.2326656 -0.0866989 -0.2668498 0.2423735 0.1066247 0.133559 0.2345219 0.1856277 0.2537414 -0.436976 0.2543982 -0.2099358 0.1907381 0.08074913 0.1702855 0.3172869 0.1765044 0.07551262 0.2325071 -0.3963208 0.1842758 -0.1389244 0.2081262 -0.1307891 -0.2457134 0.1204553 -1.478914 -0.2139639 0.1540308 0.181556 -0.3662009 -0.3680044 -0.2854581 -0.2419227 -0.261774 0.0812031 0.3495603 -0.3309193 -0.264589 -0.4655724 -0.2866207 0.1909805 0.1312949 0.108224 0.1319085 -0.1980821 -0.4409016 0.05898287 0.2418661 0.5048272 0.1583689 -0.0922055 -1.35549 -0.1912268 0.1074739 -0.1878358 -0.385833 -0.2138635 -0.284681 -0.1581863 0.2899049 -0.2023091 -0.235096 0.1669926 0.2280916 -0.1326455 -0.2694838 0.2360443 0.09828608 0.1325435 0.2381823 0.1761977 0.2469535 -0.3605106 0.2544043 -0.2156976 0.2031169 0.07546208 0.1901586 0.3433025 0.1831048 0.08327256 0.2373383 -0.383449 0.191678 -0.1426072 0.2004579 -0.1343757 -0.2405744 0.1055321 -1.345155 -0.1869687 0.1570876 0.1657929 -0.359448 -0.34321 -0.2782852 -0.2469644 -0.2580618 0.08748662 0.3638481 -0.3383436 -0.2459564 -0.4657514 -0.2823055 0.1889728 0.100636 0.1031046 0.1171434 -0.1947612 -0.4533982 0.04103864 0.2564077 0.5264368 0.1469738 -0.1180666 -1.2917 -0.1798941 0.1150065 -0.1996049 -0.4444193 -0.2162878 -0.2778559 -0.1470499 0.2976081 -0.187497 -0.2060252 0.1829642 0.2244806 -0.174946 -0.2725981 0.2304017 0.1138838 0.1315115 0.2655969 0.1945494 0.2464152 -0.374993 0.2402333 -0.2135407 0.2365418 0.0832879 0.1986987 0.3160209 0.1828792 0.07598098 0.2522934 -0.4001951 0.1796158 -0.1355257 0.182764 -0.130696 -0.2605461 0.08649756 -1.312339 -0.1629468 0.1641032 0.1417513 -0.358799 -0.3617993 -0.2974387 -0.2567271 -0.2431759 0.07090696 0.3595833 -0.3352449 -0.2695424 -0.4627208 -0.2947539 0.1901914 0.128924 0.1076577 0.1205583 -0.1818962 -0.4470941 0.04510846 0.3047965 0.552285 0.1536615 -0.1292276 -1.403507 -0.1864776 0.1431315 -0.1822529 -0.3696615 -0.1929222 -0.2923717 -0.1759784 0.3346059 -0.1719075 -0.1765259 0.2015394 0.229541 -0.184552 -0.2832578 0.2500885 0.1071925 0.1190798 0.257823 0.1954802 0.247434 -0.3962649 0.218915 -0.218306 0.2393263 0.08055634 0.1899897 0.323507 0.1932984 0.08411475 0.2469055 -0.4063187 0.1812393 -0.1409831 0.1793871 -0.1255313 -0.2529602 0.1421942 -1.330682 -0.1907565 0.1670472 0.1604107 -0.360045 -0.3602575 -0.2812704 -0.2524544 -0.2672797 0.0550609 0.385444 -0.3420538 -0.2479284 -0.4499633 -0.2921154 0.1824061 0.1092315 0.1275446 0.1257071 -0.1947283 -0.4748229 0.06702418 0.2807425 0.4841116 0.1716927 -0.09999708 -1.34429 -0.1915607 0.1055445 -0.176005 -0.4332842 -0.199822 -0.2755657 -0.1739326 0.292395 -0.2008808 -0.2133859 0.1724727 0.2254416 -0.1384496 -0.2783002 0.2519076 0.110515 0.107704 0.2334425 0.1759379 0.2685961 -0.3111562 0.2311817 -0.2251792 0.2280203 0.09222488 0.1949006 0.3373904 0.2066314 0.07689761 0.2722786 -0.388833 0.1719948 -0.1566088 0.1730165 -0.1223797 -0.2374835 0.1256883 -1.328267 -0.1895765 0.164824 0.1731038 -0.3513936 -0.3537741 -0.2787373 -0.2483812 -0.2746026 0.07401534 0.3599172 -0.3358516 -0.2619457 -0.4628556 -0.2801146 0.1854194 0.1226434 0.1378686 0.1335638 -0.1798827 -0.4531496 0.06146076 0.2893283 0.5073237 0.1581564 -0.08933603 -1.386761 -0.1946322 0.1083559 -0.1784003 -0.4653564 -0.2083236 -0.2873347 -0.157638 0.2899241 -0.2086527 -0.2427028 0.1716599 0.2261922 -0.1125006 -0.2701266 0.2389728 0.1066515 0.1328739 0.243448 0.179889 0.2462991 -0.3649818 0.243612 -0.2155089 0.2195956 0.08367826 0.1872784 0.3239369 0.1809939 0.07372983 0.2352723 -0.3901424 0.1809298 -0.1329179 0.1995282 -0.1367098 -0.2420178 0.1170807 -1.340875 -0.1812783 0.1624525 0.1671142 -0.3530988 -0.3282587 -0.2808902 -0.2411727 -0.2551767 0.07719299 0.3513891 -0.3298104 -0.2604559 -0.4634742 -0.2784229 0.2013739 0.1249456 0.1199559 0.1363274 -0.1844071 -0.4701406 0.0719572 0.2966608 0.5192926 0.1544628 -0.07669393 -1.360012 -0.2012707 0.1077544 -0.1832259 -0.3755601 -0.1974981 -0.2908662 -0.1449818 0.2953906 -0.213196 -0.2107164 0.1856415 0.2247267 -0.1019626 -0.2634943 0.224731 0.0966475 0.1316519 0.2508303 0.1811853 0.239982 -0.3524594 0.2377046 -0.2076958 0.2180899 0.08164155 0.1839629 0.3198098 0.1762757 0.0808848 0.2397834 -0.4120195 0.1814833 -0.13758 0.1846127 -0.140886 -0.2383154 0.1233149 -1.339787 -0.1811851 0.1673897 0.1691864 -0.3557721 -0.3490754 -0.2786117 -0.2462789 -0.2633727 0.06755025 0.365367 -0.3415243 -0.2555576 -0.452545 -0.2842148 0.1868897 0.1164669 0.1365385 0.1290768 -0.1827889 -0.5092799 0.06974558 0.2930483 0.5060504 0.1609729 -0.07817141 -1.367731 -0.2094538 0.1082352 -0.175706 -0.4329269 -0.2056824 -0.2858443 -0.1573784 0.2920487 -0.207516 -0.2228042 0.1885695 0.2275844 -0.09330391 -0.2578547 0.2299027 0.09323643 0.1347459 0.2432663 0.1784401 0.2461945 -0.3099489 0.220392 -0.2186679 0.2193334 0.08467321 0.1749062 0.3361356 0.1817789 0.07548992 0.2293003 -0.4059821 0.1841724 -0.1434564 0.1877122 -0.1281837 -0.2192213 0.1286457 -1.340646 -0.1908919 0.1618959 0.1629581 -0.3436026 -0.3277235 -0.2816506 -0.2452781 -0.2648166 0.07366966 0.3542248 -0.335571 -0.2560031 -0.4615178 -0.2767677 0.1922207 0.124895 0.1303889 0.1329254 -0.1929442 -0.4551441 0.0672647 0.2734668 0.4975785 0.1565812 -0.08785283 -1.286978 -0.1931857 0.1101356 -0.1752816 -0.5254196 -0.2125385 -0.2903339 -0.1661518 0.2883836 -0.2093293 -0.2189362 0.1784627 0.224708 -0.0910444 -0.2681638 0.230197 0.1045118 0.1306137 0.2483684 0.1851602 0.2467932 -0.3784296 0.2564579 -0.2097808 0.2093975 0.07943541 0.1807027 0.3296696 0.1848008 0.07933438 0.2313717 -0.4073302 0.1977986 -0.1319158 0.1593812 -0.1314062 -0.2525447 0.121517 -1.333503 -0.1931724 0.1658894 0.1822358 -0.355501 -0.3497954 -0.2713274 -0.2410548 -0.2585169 0.08369357 0.3615812 -0.3260804 -0.2681285 -0.4672661 -0.2901909 0.1995209 0.1279137 0.1311334 0.1344994 -0.1854848 -0.44785 0.05836082 0.2910197 0.5078564 0.157203 -0.08915333 -1.342413 -0.1898 0.1019358 -0.1923472 -0.3685465 -0.1933934 -0.2870483 -0.1526345 0.2871453 -0.2058686 -0.2161939 0.1630004 0.225822 -0.1365845 -0.2799428 0.2329234 0.1025876 0.129706 0.2441559 0.1837297 0.2492504 -0.3754183 0.2400335 -0.2100241 0.2228607 0.08253282 0.1987501 0.3191776 0.1800058 0.07806073 0.2501651 -0.4077091 0.1691623 -0.1453564 0.2083521 -0.1461261 -0.2497616 0.1222254 -1.330988 -0.1921324 0.1624189 0.1759284 -0.3616782 -0.3524155 -0.2760798 -0.2387044 -0.2708147 0.07417229 0.3683055 -0.3353284 -0.2716603 -0.4606954 -0.2834115 0.1945776 0.1246603 0.1250654 0.1302197 -0.1901325 -0.4598176 0.06201018 0.3095704 0.496468 0.1587241 -0.08408889 -1.343352 -0.1948133 0.1080659 -0.1801282 -0.4379064 -0.2037123 -0.2841264 -0.1545993 0.2888832 -0.2087084 -0.2202782 0.1776811 0.2257561 -0.117468 -0.2729802 0.2267464 0.09859763 0.1354834 0.2514072 0.1756469 0.2441458 -0.3657566 0.2336192 -0.2153825 0.2258901 0.08490691 0.1930054 0.326984 0.179754 0.07886518 0.2400856 -0.4010975 0.1803551 -0.1442062 0.1945922 -0.1275374 -0.2360508 0.1290045 -1.459497 -0.1950848 0.1724498 0.1515244 -0.3231929 -0.2957885 -0.2751304 -0.2536249 -0.2476967 0.07575668 0.3438293 -0.3304517 -0.2092114 -0.463798 -0.2855786 0.1820605 0.1131736 0.1426981 0.135242 -0.1772107 -0.4629759 0.07459502 0.2580035 0.5155713 0.1490774 -0.08688164 -1.287005 -0.1931027 0.1037988 -0.1751279 -0.3725062 -0.2163003 -0.3030711 -0.1637856 0.2667951 -0.2043894 -0.2348817 0.1765606 0.2284237 -0.08846145 -0.2449797 0.2461105 0.1116218 0.120027 0.2360866 0.198741 0.2524037 -0.3346904 0.2463563 -0.2131547 0.2025915 0.08406868 0.1582079 0.3041503 0.1891191 0.07360712 0.2434323 -0.4113496 0.1523684 -0.1329274 0.1625453 -0.1470105 -0.2438903 0.2193905 0.2271176 0.243983 0.2465719 0.2424621 0.2077335 0.2498853 0.2388881 0.2456406 0.238774 0.2334232 0.2443198 0.2423212" -- trained 1200000 decisions, eval return 71.3
-- BRAIN END

local ok, err = pcall(function()
  start()
  parallel.waitForAny(combat, events)
end)
-- Lasers off and hover where we are, all in the same tick
if S.learning and S.updates > 0 then pcall(saveBrain) end
local stops = { function() pcall(core.hold) end }
for _, L in ipairs(S.lasers) do stops[#stops + 1] = function() pcall(L.p.stop) end end
parallel.waitForAll(table.unpack(stops))
term.setBackgroundColor(colors.black)
term.setTextColor(colors.white)
term.clear()
term.setCursorPos(1, 1)
log("stop%s", ok and "" or (": " .. tostring(err)))
if not ok and err ~= "Terminated" then error(err, 0) end
print("UAV stopped: hovering in place, lasers off.")
