-- Test / docking helper for Create's Sticker (peripheral type "Create_Sticker").
--   sticker_test [name] [--keep]   automatic test of every sticker (also through a link hub)
--   sticker_test dock [name] [seconds]   extend and check whether the nearest ship is held (needs an AshCC radar)
--   sticker_test undock [name]     retract and check the ship can move apart again
--   sticker_test manual [name]     e: extend, r: retract, t: toggle, tab: next, q: quit
-- With Sable, an extended sticker locks onto a block of another ship (or the ground) with a fixed joint.
-- isAttachedToBlock() only sees a block in the same coordinate space, so a lock to another ship is
-- detected from motion instead: the held ship moves exactly with us.
local args = { ... }
local LOG = "sticker_test.log"
local TYPE = "Create_Sticker"

local function now() return os.epoch("utc") / 1000 end

local logFile
local function log(fmt, ...)
  local line = string.format(fmt, ...)
  print(line)
  if logFile then logFile.writeLine(line) end
end

local function findStickers(only)
  local list = {}
  for _, name in ipairs(peripheral.getNames()) do
    if peripheral.hasType(name, TYPE) and (not only or name == only) then
      list[#list + 1] = { name = name, p = peripheral.wrap(name) }
    end
  end
  table.sort(list, function(a, b) return a.name < b.name end)
  return list
end

local function need(list, only)
  if #list == 0 then error("No sticker found" .. (only and (": " .. only) or ""), 0) end
  return list
end

-- The block state changes on the main thread; give it a couple of ticks
local function settle() sleep(0.15) end

---------------------------------------------------------------- motion helpers
local radar = peripheral.find("radar")

-- Our own velocity: the ship we are on (CC: Sable), or zero on the ground
local function selfVelocity()
  if sublevel and sublevel.isInPlotGrid and sublevel.isInPlotGrid() then
    local v = sublevel.getLinearVelocity()
    return { x = v.x, y = v.y, z = v.z }, sublevel.getUniqueId and sublevel.getUniqueId() or nil
  end
  return { x = 0, y = 0, z = 0 }, nil
end

local function nearestShip(range)
  if not radar then return nil end
  local ships = radar.scanSubLevels(range or 64)
  return ships[1]
end

-- Samples the relative velocity between us and the target ship for `seconds`.
-- Returns max relative speed, max own speed (to tell whether the test means anything), samples, last contact
local function sampleRelative(id, seconds)
  local t0, maxRel, maxSelf, n, last = now(), 0, 0, 0, nil
  while now() - t0 < seconds do
    local c = radar.getContact(id)
    if not c then return nil end
    local v = selfVelocity()
    local rel = math.sqrt((c.vx - v.x) ^ 2 + (c.vy - v.y) ^ 2 + (c.vz - v.z) ^ 2)
    local own = math.max(math.sqrt(v.x ^ 2 + v.y ^ 2 + v.z ^ 2), math.sqrt(c.vx ^ 2 + c.vy ^ 2 + c.vz ^ 2))
    maxRel, maxSelf, n, last = math.max(maxRel, rel), math.max(maxSelf, own), n + 1, c
    sleep(0.1)
  end
  return maxRel, maxSelf, n, last
end

local LOCK_REL = 0.05   -- blocks/s; a held ship moves with us
local MOVING = 0.5      -- blocks/s; below this both are (almost) still and the check proves little

---------------------------------------------------------------- auto
local function autoTest(s, keep)
  local p, pass, fail = s.p, 0, 0
  local function check(ok, fmt, ...)
    if ok then pass = pass + 1 else fail = fail + 1 end
    log("  [%s] " .. fmt, ok and "PASS" or "FAIL", ...)
  end
  log("== %s", s.name)
  local wasExtended = p.isExtended()
  log("  initial: extended=%s attachedToBlock=%s", tostring(wasExtended), tostring(p.isAttachedToBlock()))

  if wasExtended then p.retract(); settle() end
  check(not p.isExtended(), "retracted")
  check(not p.isAttachedToBlock(), "not attached while retracted")
  check(p.retract() == false, "retract() again reports no change")

  check(p.extend() == true, "extend() reports a change")
  settle()
  check(p.isExtended(), "extended")
  check(p.extend() == false, "extend() again reports no change")
  log("  attachedToBlock while extended: %s (true only for a block in the same world/ship)", tostring(p.isAttachedToBlock()))

  check(p.toggle() == true, "toggle() reports a change")
  settle()
  check(not p.isExtended(), "toggle() retracted it")
  check(p.toggle() == true, "toggle() again")
  settle()
  check(p.isExtended(), "toggle() extended it")

  -- A redstone signal also drives the sticker and can undo what Lua does
  local side = s.name:match("^(%a+)$")
  if side and redstone.getInput(side) then log("  note: redstone input on %s", side) end

  local wanted = keep and wasExtended or false
  if p.isExtended() ~= wanted then p.toggle(); settle() end
  check(p.isExtended() == wanted, "left %s", wanted and "extended" or "retracted")
  return pass, fail
end

---------------------------------------------------------------- dock / undock
local function dock(s, seconds)
  if not radar then error("dock needs an AshCC radar (to watch the other ship)", 0) end
  local ship = nearestShip()
  if not ship then error("No other ship within 64 blocks", 0) end
  log("Target ship %s %s, %.1f blocks away", ship.name or "?", ship.id:sub(1, 8), ship.distance)
  if ship.distance > 2 then log("  warning: the sticker must touch the other ship's block face") end

  if not s.p.isExtended() then s.p.extend() end
  settle()
  log("Sticker extended; watching relative motion for %.0f s ...", seconds)
  local rel, moving, n = sampleRelative(ship.id, seconds)
  if not rel then log("Lost the target ship"); return false end
  log("  samples %d, max relative speed %.3f b/s, max speed %.2f b/s", n, rel, moving)
  if rel < LOCK_REL and moving >= MOVING then
    log("[LOCKED] the ship moves with us")
    return true
  elseif rel < LOCK_REL then
    log("[STILL] no relative motion, but nothing moved: move one ship and run again")
    return nil
  end
  log("[FREE] the ship moves independently (not held)")
  return false
end

local function undock(s, seconds)
  s.p.retract()
  settle()
  log("Sticker retracted (extended=%s)", tostring(s.p.isExtended()))
  if not radar then return true end
  local ship = nearestShip()
  if not ship then return true end
  local rel, moving = sampleRelative(ship.id, seconds)
  if rel then log("  max relative speed %.3f b/s (%s)", rel, rel >= LOCK_REL and "separating" or "not moving apart yet") end
  return true
end

---------------------------------------------------------------- manual
local function manual(list)
  local i = 1
  local function draw()
    local s = list[i]
    term.clear()
    term.setCursorPos(1, 1)
    print(("Sticker %d/%d: %s"):format(i, #list, s.name))
    print(("extended: %-5s  attachedToBlock: %s"):format(tostring(s.p.isExtended()), tostring(s.p.isAttachedToBlock())))
    local ship = nearestShip()
    if ship then
      local v = selfVelocity()
      local rel = math.sqrt((ship.vx - v.x) ^ 2 + (ship.vy - v.y) ^ 2 + (ship.vz - v.z) ^ 2)
      print(("nearest ship %.1f blocks, rel speed %.2f b/s"):format(ship.distance, rel))
    end
    print("")
    print("e: extend  r: retract  t: toggle")
    print("tab: next sticker  q: quit")
  end
  local timer = os.startTimer(0.25)
  draw()
  while true do
    local ev, key = os.pullEvent()
    local p = list[i].p
    if ev == "timer" and key == timer then timer = os.startTimer(0.25)
    elseif ev == "key" then
      if key == keys.q then break
      elseif key == keys.e then p.extend()
      elseif key == keys.r then p.retract()
      elseif key == keys.t then p.toggle()
      elseif key == keys.tab then i = i % #list + 1 end
    end
    draw()
  end
  term.clear()
  term.setCursorPos(1, 1)
end

---------------------------------------------------------------- main
local mode = args[1]
if mode == "dock" or mode == "undock" or mode == "manual" then
  local only = args[2]
  if only and tonumber(only) then only = nil end
  local list = need(findStickers(only), only)
  if mode == "manual" then manual(list)
  elseif mode == "dock" then dock(list[1], tonumber(args[3] or args[2]) or 3)
  else undock(list[1], 2) end
elseif mode == "help" or mode == "-h" then
  print("Usage: sticker_test [name] [--keep] | dock [name] [seconds] | undock [name] | manual [name]")
else
  local only, keep = nil, false
  for _, a in ipairs(args) do
    if a == "--keep" then keep = true elseif a ~= "auto" then only = only or a end
  end
  local list = need(findStickers(only), only)
  logFile = fs.open(LOG, "w")
  log("Sticker test, %d sticker(s)%s", #list, radar and ", radar found" or "")
  local pass, fail = 0, 0
  for _, s in ipairs(list) do
    local ok, a, b = pcall(autoTest, s, keep)
    if ok then pass, fail = pass + a, fail + b
    else fail = fail + 1; log("  [FAIL] error: %s", tostring(a)) end
  end
  log("== %d passed, %d failed", pass, fail)
  logFile.close()
  print("Log written to " .. LOG)
end
