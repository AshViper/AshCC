-- Laser turret / test program (peripheral type "laser").
--   laser_turret [name]                     automatic test: aim limits, trace, fire, stop (laser_test.log)
--   laser_turret track [range] [name]       auto turret: aim at the nearest Sable ship (radar or seeker) and fire
--   laser_turret manual [name]              arrows aim, space fire on/off, t trace, q quit
-- The laser aims anywhere: yaw 0 = north, positive towards east; pitch positive up (the ship's frame on a ship).
-- Works on a ship too: aimAt() takes world coordinates and is re-aimed every tick as the ship turns.
local args = { ... }
local LOG = "laser_test.log"

local logFile
local function log(fmt, ...)
  local line = string.format(fmt, ...)
  print(line)
  if logFile then logFile.writeLine(line) end
end

local function findLaser(only)
  for _, name in ipairs(peripheral.getNames()) do
    if peripheral.hasType(name, "laser") and (not only or name == only) then
      return name, peripheral.wrap(name)
    end
  end
  error("No laser found" .. (only and (": " .. only) or ""), 0)
end

local function describe(h)
  if not h then return "nil" end
  local what = h.block or (h.entity and h.entity.type) or ""
  return ("%s %s %.1f m%s"):format(h.type, what, h.distance, h.subLevel and (" ship " .. h.subLevel:sub(1, 8)) or "")
end

---------------------------------------------------------------- auto
local function autoTest(name, laser)
  local pass, fail = 0, 0
  local function check(ok, fmt, ...)
    if ok then pass = pass + 1 else fail = fail + 1 end
    log("  [%s] " .. fmt, ok and "PASS" or "FAIL", ...)
  end
  log("== %s (range %.0f)", name, laser.getRange())

  local y, p = laser.setAim(190, 100)
  check(math.abs(y + 170) < 1e-6 and p == 90, "setAim wraps yaw and clamps pitch (%.1f, %.1f)", y, p)
  check(not pcall(laser.setAim, 0 / 0, 0), "NaN rejected")
  check(not pcall(laser.fire, -1), "negative fire time rejected")

  laser.setAim(0, 0)
  local t0 = os.clock()
  while not laser.isAimed() and os.clock() - t0 < 3 do sleep(0.05) end
  check(laser.isAimed(), "aim reached north (yaw 0)")

  local d = laser.getDirection()
  local ok, ay, ap = laser.aimAt(d.origin.x + d.x * 10, d.origin.y + d.y * 10, d.origin.z + d.z * 10)
  check(ok and math.abs(ay) < 0.5 and math.abs(ap) < 0.5, "aimAt a point straight ahead -> (%.2f, %.2f)", ay, ap)
  local okb, by, bp = laser.aimAt(d.origin.x - d.x * 10, d.origin.y - d.y * 10, d.origin.z - d.z * 10)
  check(okb and math.abs(math.abs(by) - 180) < 1 and math.abs(bp) < 1, "aimAt a point behind -> (%.2f, %.2f)", by, bp)
  local okd, _, dp = laser.aimAt(d.origin.x, d.origin.y - 10, d.origin.z)
  check(okd and dp < -85, "aimAt a point below -> pitch %.2f", dp)
  laser.setAim(0, 0)
  sleep(0.5)

  local tr = laser.trace()
  log("  trace: %s", describe(tr))
  check(tr.type ~= nil and tr.distance >= 0, "trace returns a result")

  if laser.isOverheated() then
    log("  overheated, skipping fire test (heat %.2f)", laser.getHeat())
  else
    check(laser.fire(1) == true, "fire(1)")
    sleep(0.2)
    check(laser.isFiring(), "firing")
    log("  hit: %s  heat %.2f", describe(laser.getHit()), laser.getHeat())
    sleep(1.2)
    check(not laser.isFiring(), "fire(1) stopped by itself")
    check(laser.getHit() == nil, "getHit() is nil when not firing")
  end
  laser.setRedstoneControl(true)
  check(laser.isRedstoneControl(), "back to redstone control")
  log("  %d passed, %d failed", pass, fail)
  return pass, fail
end

---------------------------------------------------------------- track
local function nearestShip(range)
  local radar = peripheral.find("radar")
  if radar then
    local subs = radar.scanSubLevels(range)
    return subs[1]
  end
  local seeker = peripheral.find("seeker")
  if seeker then return seeker.seek(90, range) end
  error("track needs a radar or a seeker", 0)
end

local function track(laser, range)
  local hits, status = 0, ""
  local function aimLoop()
    while true do
      local t = nearestShip(range)
      if t then
        local ok = laser.aimAt(t.x, t.y, t.z)
        local aimed = ok and laser.isAimed()
        if aimed and not laser.isOverheated() then
          if not laser.isFiring() then laser.fire() end
        elseif laser.isFiring() then
          laser.stop()
        end
        status = ("%s  dist %.0f  %s"):format(t.name or t.id:sub(1, 8), t.centerDistance or t.distance,
          not ok and "out of arc" or (aimed and "FIRING" or "aiming"))
      else
        if laser.isFiring() then laser.stop() end
        status = "no target"
      end
      term.clear()
      term.setCursorPos(1, 1)
      print("Laser turret  (Ctrl+T to stop)")
      print(status)
      print(("heat %3d%%%s"):format(laser.getHeat() * 100, laser.isOverheated() and "  OVERHEAT" or ""))
      print("blocks burnt: " .. hits)
      local h = laser.getHit()
      if h then print("hit: " .. describe(h)) end
      sleep(0.05)
    end
  end
  local function events()
    while true do
      local ev = { os.pullEvent() }
      if ev[1] == "laser_break" then hits = hits + 1 end
    end
  end
  local ok, err = pcall(parallel.waitForAny, aimLoop, events)
  laser.stop()
  if not ok and err ~= "Terminated" then error(err, 0) end
end

---------------------------------------------------------------- manual
local function manual(laser)
  local yaw, pitch, step = 0, 0, 2
  laser.setAim(0, 0)
  while true do
    term.clear()
    term.setCursorPos(1, 1)
    print("arrows aim, space fire, t trace, q quit")
    local ay, ap = laser.getAim()
    print(("aim %.1f %.1f  target %.1f %.1f"):format(ay, ap, yaw, pitch))
    print(("firing %s  heat %3d%%"):format(tostring(laser.isFiring()), laser.getHeat() * 100))
    print("hit: " .. describe(laser.getHit()))
    local _, key = os.pullEvent("key")
    if key == keys.left then yaw = yaw - step
    elseif key == keys.right then yaw = yaw + step
    elseif key == keys.up then pitch = pitch + step
    elseif key == keys.down then pitch = pitch - step
    elseif key == keys.space then
      if laser.isFiring() then laser.stop() else laser.fire() end
    elseif key == keys.t then
      print("trace: " .. describe(laser.trace()))
      sleep(1)
    elseif key == keys.q then
      laser.stop()
      return
    end
    yaw, pitch = laser.setAim(yaw, pitch)
  end
end

----------------------------------------------------------------
if args[1] == "track" then
  local range = tonumber(args[2]) or 256
  local _, laser = findLaser(args[3])
  track(laser, range)
elseif args[1] == "manual" then
  local _, laser = findLaser(args[2])
  manual(laser)
else
  local name, laser = findLaser(args[1])
  logFile = fs.open(LOG, "w")
  local ok, err = pcall(autoTest, name, laser)
  if not ok then log("ERROR: %s", tostring(err)) end
  logFile.close()
end
