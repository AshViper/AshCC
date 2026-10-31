-- Create Big Cannons turret battery: an AshCC radar finds targets, every connected cannon_mount engages them.
--   cbc_turret [range] [--hold] [--mobs]
--     range   search range in blocks (default CFG.range)
--     --hold  start with weapons on hold (space to arm)
--     --mobs  also engage flying mobs (CFG.mobs)
-- Keys: space arm/hold, f mark the top target friendly, c clear friends, q quit
-- Each cannon leads its target with mount.aimAt, which simulates the loaded round's flight (gravity and drag),
-- then fires once it is on target: big cannons and railguns one shot per fire(), autocannons, rotary
-- autocannons and laser cannons hold the trigger while on target. Fire is held while a friendly ship is near the line of fire.
-- Works on a ship too: aimAt converts into the mount's (ship's) frame every cycle.
local args = { ... }

local CFG = {
  range = 512,
  minAltitude = 4,        -- bottom of a target must be this far above the radar (blocks); -1000 = ground targets too
  mobs = { "minecraft:phantom", "minecraft:ghast", "minecraft:blaze" },
  engageMobs = false,
  aimSpeed = 90,          -- mount turn rate (degrees/s, capped by the server's maxAimSpeed)
  onTarget = 1.0,         -- degrees between the aim and the solution to count as on target
  friendCone = 4,         -- degrees: hold fire when a friend is this close to the line of fire and nearer than the target
  highArc = false,        -- use the high trajectory (mortar-like) instead of the direct one
  cycle = 0.1,            -- seconds between updates
  friendsFile = "turret_friends.txt",
  friendCodes = {},       -- IFF codes always treated as friendly, e.g. { BLUE = true }
  trustOwnCode = true,    -- ships sending the same IFF code as our own ship are friendly
  logFile = "cbc_turret.log",  -- engagements, shots and errors (nil = no log)
  logLimit = 64 * 1024,
}

local function log(fmt, ...)
  if not CFG.logFile then return end
  local line = ("[%s] " .. fmt):format(textutils.formatTime(os.time(), true), ...)
  pcall(function()
    if fs.exists(CFG.logFile) and fs.getSize(CFG.logFile) > CFG.logLimit then fs.delete(CFG.logFile) end
    local f = fs.open(CFG.logFile, "a")
    f.writeLine(line)
    f.close()
  end)
end

local S = { armed = true, running = true, friends = {}, targets = {}, cannons = {}, self = nil }

for _, a in ipairs(args) do
  if a == "--hold" then S.armed = false
  elseif a == "--mobs" then CFG.engageMobs = true
  elseif tonumber(a) then CFG.range = tonumber(a) end
end

---------------------------------------------------------------- friends
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

local function isFriend(t)
  if S.friends[t.id] or (t.name and S.friends[t.name]) then return true end
  local own = S.self
  -- our own chaff shows up with exactly our ship's mass and size
  if own and own.mass and t.mass == own.mass and t.size and own.size and t.size.x == own.size.x
    and t.size.y == own.size.y and t.size.z == own.size.z then return true end
  local code = t.iff and t.iff.code
  if not code then return false end
  if CFG.friendCodes[code] then return true end
  return CFG.trustOwnCode and own ~= nil and own.iff ~= nil and own.iff.code == code
end

local function label(t)
  local callsign = t.iff and t.iff.callsign ~= "" and t.iff.callsign
  return t.name or callsign or (t.type and t.type:gsub("^minecraft:", "")) or t.id:sub(1, 8)
end

---------------------------------------------------------------- peripherals
local radar = peripheral.find("radar")
if not radar then error("No radar found", 0) end
for _, name in ipairs(peripheral.getNames()) do
  if peripheral.hasType(name, "cannon_mount") then
    local p = peripheral.wrap(name)
    S.cannons[#S.cannons + 1] = { name = name, p = p, status = "IDLE" }
    pcall(p.setAimSpeed, CFG.aimSpeed)
  end
end
if #S.cannons == 0 then error("No cannon_mount found", 0) end
table.sort(S.cannons, function(a, b) return a.name < b.name end)
loadFriends()
do
  local names = {}
  for _, c in ipairs(S.cannons) do names[#names + 1] = c.name end
  log("start: range %d, cannons %s", CFG.range, table.concat(names, ", "))
end

---------------------------------------------------------------- scan
local function scan()
  local ships, mobs, self, pos = {}, {}, nil, nil
  local tasks = {
    function() ships = radar.scanSubLevels(CFG.range) end,
    function() self = radar.getSelf() end,
    function() pos = radar.getPosition() end,
  }
  if CFG.engageMobs then tasks[#tasks + 1] = function() mobs = radar.scanEntities(CFG.range, { type = CFG.mobs }) end end
  parallel.waitForAll(table.unpack(tasks))
  S.self = self
  local list, friends = {}, {}
  for _, group in ipairs({ ships, mobs }) do
    for _, t in ipairs(group) do
      local bottom = t.bounds and t.bounds.minY or t.y
      if isFriend(t) then
        friends[#friends + 1] = t
      elseif pos == nil or bottom - pos.y >= CFG.minAltitude then
        list[#list + 1] = t
      end
    end
  end
  -- nearest first, approaching targets count as closer
  for _, t in ipairs(list) do
    local closing = 0
    if pos then
      local dx, dy, dz = t.x - pos.x, t.y - pos.y, t.z - pos.z
      local d = math.max(1, math.sqrt(dx * dx + dy * dy + dz * dz))
      closing = -(dx * t.vx + dy * t.vy + dz * t.vz) / d
    end
    t.score = t.distance - 3 * closing
  end
  table.sort(list, function(a, b) return a.score < b.score end)
  S.targets, S.friendList = list, friends
end

---------------------------------------------------------------- engage
local function angleTo(dir, origin, p)
  local dx, dy, dz = p.x - origin.x, p.y - origin.y, p.z - origin.z
  local d = math.sqrt(dx * dx + dy * dy + dz * dz)
  if d < 1e-6 then return 0, 0 end
  local c = (dx * dir.x + dy * dir.y + dz * dir.z) / d
  return math.deg(math.acos(math.max(-1, math.min(1, c)))), d
end

local function friendInTheWay(m, target)
  local _, td = angleTo(m.direction, m.pivot, target)
  for _, f in ipairs(S.friendList or {}) do
    local a, d = angleTo(m.direction, m.pivot, f)
    if a < CFG.friendCone and d < td then return f end
  end
end

local function trigger(c, info, on)
  local auto = info.type == "autocannon" or info.type == "rotary_autocannon" or info.type == "laser_cannon"
  if auto then
    if on ~= c.firing then
      pcall(c.p.setFiring, on)
      c.firing = on
    end
  elseif on then
    local ok, why = c.p.fire()
    if ok then
      c.shots = (c.shots or 0) + 1
      log("%s fired shot %d at %s (%.1fs flight)", c.name, c.shots, c.target or "?", c.flight or 0)
    elseif why ~= "cooling down" and why ~= c.lastWhy then
      log("%s did not fire: %s", c.name, tostring(why))
    end
    c.lastWhy = why
  end
end

local function engage(c)
  local p = c.p
  local info = p.getCannon()
  if not info then
    c.status = "NO CANNON"
    return
  end
  local target = S.targets[1]
  if not target then
    trigger(c, info, false)
    c.status = "IDLE"
    return
  end
  local ok, yaw, pitch, t = p.aimAt(target.x, target.y, target.z,
    { velocity = { x = target.vx, y = target.vy, z = target.vz }, high = CFG.highArc })
  if not ok then
    trigger(c, info, false)
    c.status = tostring(yaw)  -- the reason
    return
  end
  local ay, ap = p.getAim()
  local err = math.max(math.abs((ay - yaw + 180) % 360 - 180), math.abs(ap - pitch))
  c.target, c.flight = label(target), t
  local onTarget = err <= CFG.onTarget
  local m = p.getMuzzle()
  local friend = onTarget and friendInTheWay(m, target)
  if friend then
    c.status = "FRIEND " .. label(friend)
    trigger(c, info, false)
  elseif not S.armed then
    c.status = "HOLD"
    trigger(c, info, false)
  else
    c.status = onTarget and "FIRE" or ("AIM %.1f"):format(err)
    trigger(c, info, onTarget)
  end
end

---------------------------------------------------------------- display
local function draw()
  term.clear()
  term.setCursorPos(1, 1)
  print(("CBC turret  %s  targets %d  [space] arm/hold [f] friend [c] clear [q] quit")
    :format(S.armed and "ARMED" or "HOLD", #S.targets))
  for i = 1, math.min(3, #S.targets) do
    local t = S.targets[i]
    print(("%d %-16s %5.0fm  v%4.0f"):format(i, label(t):sub(1, 16), t.distance, math.sqrt(t.vx ^ 2 + t.vy ^ 2 + t.vz ^ 2)))
  end
  print()
  for _, c in ipairs(S.cannons) do
    print(("%-18s %-14s %s %s"):format(c.name:sub(1, 18), (c.status or ""):sub(1, 14),
      c.target and c.target:sub(1, 12) or "", c.flight and ("%.1fs"):format(c.flight) or ""))
  end
end

local function loop()
  while S.running do
    scan()
    for _, c in ipairs(S.cannons) do
      local ok, err = pcall(engage, c)
      if not ok then c.status = "ERR " .. tostring(err) end
      -- log what each cannon does when it changes (statuses with numbers in them change every cycle)
      local kind = (c.status or ""):match("^(%a+)") or ""
      if kind ~= c.loggedKind or c.target ~= c.loggedTarget then
        log("%s %s %s", c.name, c.status or "", c.target or "")
        c.loggedKind, c.loggedTarget = kind, c.target
      end
    end
    draw()
    sleep(CFG.cycle)
  end
end

local function keyLoop()
  while S.running do
    local _, k = os.pullEvent("key")
    if k == keys.space then S.armed = not S.armed
    elseif k == keys.f and S.targets[1] then addFriend(S.targets[1].id)
    elseif k == keys.c then fs.delete(CFG.friendsFile); S.friends = {}
    elseif k == keys.q then S.running = false end
  end
end

local ok, err = pcall(parallel.waitForAny, loop, keyLoop)
if not ok then log("stopped by error: %s", tostring(err)) end
for _, c in ipairs(S.cannons) do pcall(c.p.setFiring, false) end
term.clear()
term.setCursorPos(1, 1)
log("stopped")
if not ok then error(err, 0) end
print("cbc_turret stopped")
