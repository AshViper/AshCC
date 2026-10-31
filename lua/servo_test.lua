-- Servo bearing test program.
--   servo_test                        automatic test of every servo bearing (works through a link hub too)
--   servo_test auto [name] [--keep]   same, one bearing only; --keep leaves it assembled
--   servo_test manual [name]          drive it with the keyboard
--   servo_test wave [amp] [period]    swing all bearings (sine)
--   servo_test spin [deg/s]           turn all bearings round and round (turret style; negative = other way)
-- The turntable is a Sable ship held by a joint, so angles are measured, not exact: a move counts as reached
-- within 1.5 degrees, and speeds within +-35%.
local args = { ... }
local mode = args[1] or "auto"
local LOG = "servo_test.log"
local REACH = 1.5 -- degrees

local function now() return os.epoch("utc") / 1000 end

local function wrap(a)
  a = (a + 180) % 360 - 180
  return a
end

local function findServos(only)
  local list = {}
  for _, name in ipairs(peripheral.getNames()) do
    if peripheral.hasType(name, "servo_bearing") and (not only or name == only) then
      list[#list + 1] = { name = name, p = peripheral.wrap(name) }
    end
  end
  table.sort(list, function(a, b) return a.name < b.name end)
  return list
end

local logFile
local function log(fmt, ...)
  local line = string.format(fmt, ...)
  print(line)
  if logFile then logFile.writeLine(line) end
end

local function ensureAssembled(s)
  if s.p.isAssembled() then return true end
  local ok, why = s.p.assemble()
  if not ok then log("  assemble failed: %s", tostring(why)) end
  return ok
end

-- Wait until the bearing reports it reached the target (or timeout). Returns elapsed seconds or nil.
-- onTick(angle) is called with each measured angle while waiting.
local function waitTarget(p, timeout, onTick)
  local t0 = now()
  while now() - t0 < timeout do
    if onTick then onTick(p.getAngle()) end
    if p.isAtTarget() then return now() - t0 end
    sleep(0.05)
  end
  return nil
end

---------------------------------------------------------------- auto
local function autoTest(s, keep)
  local p, pass, fail = s.p, 0, 0
  local function check(ok, fmt, ...)
    if ok then pass = pass + 1 else fail = fail + 1 end
    log("  [%s] " .. fmt, ok and "PASS" or "FAIL", ...)
  end

  log("== %s", s.name)
  local ax = p.getAxis()
  log("  axis (world) %.2f %.2f %.2f, max speed %.0f deg/s", ax.x, ax.y, ax.z, p.getMaxSpeed())
  check(math.abs(math.sqrt(ax.x ^ 2 + ax.y ^ 2 + ax.z ^ 2) - 1) < 1e-3, "axis is a unit vector")
  check(ensureAssembled(s), "assemble")
  if not p.isAssembled() then return pass, fail end
  local id = p.getSubLevel()
  check(type(id) == "string" and #id == 36, "turntable sub-level %s", tostring(id))
  if not p.isLocked() then p.setLocked(true) end

  -- Argument checks
  check(not pcall(p.setAngle, 0 / 0), "setAngle(nan) is rejected")
  check(not pcall(p.setSpeed, math.huge), "setSpeed(inf) is rejected")
  check(p.setSpeed(1e9) <= p.getMaxSpeed(), "speed is clamped to %.0f", p.getMaxSpeed())
  check(p.setSpeed(0) >= 1, "speed 0 is raised to the minimum")
  check(p.setAngle(270) == -90, "270 wraps to -90")
  local w = p.setAngle(-540)
  check(math.abs(w) == 180, "-540 wraps to %.0f", w)
  local speed = math.min(90, p.getMaxSpeed())
  check(p.setSpeed(speed) == speed, "speed set to %.0f deg/s", speed)

  -- Start from 0
  p.setAngle(0)
  waitTarget(p, 360 / speed + 3)

  -- Sweep: each move is timed and compared with distance / speed
  for _, target in ipairs({ 30, -30, 90, -90, 0 }) do
    local from = p.getAngle()
    local set = p.setAngle(target)
    local dist = math.abs(wrap(set - from))
    local expected = dist / speed
    local took = waitTarget(p, expected * 2 + 3)
    local final = p.getAngle()
    if took then
      check(math.abs(wrap(final - target)) < REACH, "%6.1f -> %6.1f  final %6.2f  %.2fs (expected %.2fs)", from, target, final, took, expected)
      if expected > 0.5 then
        local measured = dist / took
        check(measured > speed * 0.65 and measured < speed * 1.35, "   measured speed %.0f deg/s", measured)
      end
    else
      check(false, "%6.1f -> %6.1f  timed out at %6.2f", from, target, final)
    end
  end

  -- Crossing 180: 150 -> -150 must go the short way (60 deg through 180), not 300 deg back through 0
  p.setSpeed(p.getMaxSpeed())
  p.setAngle(150)
  waitTarget(p, 5)
  p.setSpeed(speed)
  local minAbs = 180
  p.setAngle(-150)
  local took = waitTarget(p, 60 / speed * 2 + 3, function(a) minAbs = math.min(minAbs, math.abs(a)) end)
  check(took ~= nil and minAbs > 120, "150 -> -150 the short way (closest to 0 was %.1f deg, %s)", minAbs,
    took and string.format("%.2fs", took) or "timeout")

  -- Holding: the angle must stay put once there
  p.setAngle(45)
  waitTarget(p, 360 / speed + 3)
  sleep(0.5) -- let it settle
  local a0, maxDrift, t0 = p.getAngle(), 0, now()
  while now() - t0 < 2 do
    maxDrift = math.max(maxDrift, math.abs(wrap(p.getAngle() - a0)))
    sleep(0.1)
  end
  check(maxDrift < 1, "holds 45 deg for 2 s (drift %.2f deg)", maxDrift)

  -- Unlock / lock
  p.setLocked(false)
  check(not p.isLocked() and not p.isAtTarget(), "unlocked: free, not at target")
  sleep(0.5)
  p.setLocked(true)
  check(p.isLocked(), "locked again")
  check(p.getTargetAngle() == 45, "target kept while unlocked")
  check(waitTarget(p, 360 / speed + 3) ~= nil, "returns to the target after locking (at %.2f)", p.getAngle())

  -- Fast move
  local fast = p.setSpeed(p.getMaxSpeed())
  p.setAngle(-45)
  took = waitTarget(p, 90 / fast + 3)
  check(took ~= nil, "fast move 45 -> -45 at %.0f deg/s took %s", fast, took and string.format("%.2fs", took) or "timeout")
  p.setSpeed(speed)

  -- Disassemble (returns to 0 first)
  if not keep then
    if not p.disassemble() then
      check(p.getTargetAngle() == 0, "disassemble: returning to 0 first")
      local t1 = now()
      while p.isAssembled() and now() - t1 < 360 / speed + 8 do sleep(0.05) end
    end
    check(not p.isAssembled(), "disassembled")
    check(p.getSubLevel() == nil, "no sub-level after disassembly")
  else
    p.setAngle(0)
    waitTarget(p, 360 / speed + 3)
  end
  return pass, fail
end

---------------------------------------------------------------- manual
local function manual(servos)
  local i, step, msg = 1, 5, nil
  local function draw()
    local s = servos[i]
    term.clear()
    term.setCursorPos(1, 1)
    print(("Servo %d/%d: %s"):format(i, #servos, s.name))
    print(("assembled: %s   %s"):format(tostring(s.p.isAssembled()), s.p.isLocked() and "locked" or "UNLOCKED (free)"))
    print(("angle  %7.2f   target %7.2f  %s"):format(s.p.getAngle(), s.p.getTargetAngle(), s.p.isAtTarget() and "at target" or "moving"))
    print(("speed  %7.0f deg/s   step %d deg"):format(s.p.getSpeed(), step))
    print(("sub-level %s"):format(tostring(s.p.getSubLevel())))
    print("")
    print("left/right: angle -/+   up/down: speed")
    print("1-9: step   space: 0 deg   l: lock/unlock")
    print("a: assemble   d: disassemble")
    print("tab: next servo   q: quit")
    if msg then print(msg); msg = nil end
  end
  local timer = os.startTimer(0.2)
  draw()
  while true do
    local ev, key = os.pullEvent()
    local p = servos[i].p
    if ev == "timer" and key == timer then
      timer = os.startTimer(0.2)
    elseif ev == "key" then
      if key == keys.q then break
      elseif key == keys.left then p.setAngle(p.getTargetAngle() - step)
      elseif key == keys.right then p.setAngle(p.getTargetAngle() + step)
      elseif key == keys.up then p.setSpeed(p.getSpeed() * 1.5)
      elseif key == keys.down then p.setSpeed(p.getSpeed() / 1.5)
      elseif key == keys.space then p.setAngle(0)
      elseif key == keys.l then p.setLocked(not p.isLocked())
      elseif key == keys.a then
        local ok, why = p.assemble()
        if not ok then msg = "assemble failed: " .. tostring(why) end
      elseif key == keys.d then
        if not p.disassemble() then msg = "returning to 0, then disassembling" end
      elseif key == keys.tab then i = i % #servos + 1
      end
    elseif ev == "char" and tonumber(key) then
      step = tonumber(key)
    end
    draw()
  end
  term.clear()
  term.setCursorPos(1, 1)
end

---------------------------------------------------------------- wave / spin
local function prepare(servos)
  for _, s in ipairs(servos) do
    if ensureAssembled(s) then
      s.p.setLocked(true)
      s.p.setSpeed(s.p.getMaxSpeed())
    end
  end
end

-- Calls angleAt(t) every tick for every bearing until Ctrl+T, then returns them to 0.
local function drive(servos, angleAt)
  local t0 = now()
  local ok, err = pcall(function()
    while true do
      local a = angleAt(now() - t0)
      local calls = {}
      for _, s in ipairs(servos) do
        calls[#calls + 1] = function() if s.p.isAssembled() then s.p.setAngle(a) end end
      end
      parallel.waitForAll(table.unpack(calls)) -- same tick for all bearings
      local _, y = term.getCursorPos()
      term.setCursorPos(1, y)
      term.clearLine()
      term.write(("command %7.1f  actual %7.1f"):format(wrap(a), servos[1].p.getAngle()))
      sleep(0.05)
    end
  end)
  for _, s in ipairs(servos) do pcall(s.p.setAngle, 0) end
  print("")
  if not ok and err ~= "Terminated" then error(err, 0) end
end

local function wave(servos, amp, period)
  prepare(servos)
  print(("Waving %d servo(s): +-%.0f deg, period %.1f s. Ctrl+T to stop."):format(#servos, amp, period))
  drive(servos, function(t) return amp * math.sin(2 * math.pi * t / period) end)
end

local function spin(servos, rate)
  prepare(servos)
  print(("Spinning %d servo(s) at %.0f deg/s. Ctrl+T to stop."):format(#servos, rate))
  -- The target moves on in small steps, so the short way is always forwards
  drive(servos, function(t) return rate * t end)
end

---------------------------------------------------------------- main
if mode == "manual" then
  local servos = findServos(args[2])
  if #servos == 0 then error("No servo bearing found", 0) end
  manual(servos)
elseif mode == "wave" then
  local servos = findServos()
  if #servos == 0 then error("No servo bearing found", 0) end
  wave(servos, tonumber(args[2]) or 45, tonumber(args[3]) or 4)
elseif mode == "spin" then
  local servos = findServos()
  if #servos == 0 then error("No servo bearing found", 0) end
  local rate = tonumber(args[2]) or 30
  -- Faster than the servo can follow, the target would get more than 180 deg ahead and the short way would reverse
  local max = servos[1].p.getMaxSpeed()
  if rate == 0 or math.abs(rate) > max then error(("spin rate must be non-zero and at most %.0f deg/s"):format(max), 0) end
  spin(servos, rate)
elseif mode == "auto" or mode == "--keep" or (mode ~= "help" and not mode:match("^%-")) then
  -- auto [name] [--keep]; the first word may also be a bearing name
  local only, keep = nil, false
  for i = 1, #args do
    if args[i] == "--keep" then keep = true
    elseif not (i == 1 and args[i] == "auto") then only = only or args[i] end
  end
  local servos = findServos(only)
  if #servos == 0 then error("No servo bearing found" .. (only and (": " .. only) or ""), 0) end
  logFile = fs.open(LOG, "w")
  log("Servo bearing test, %d bearing(s)", #servos)
  local pass, fail = 0, 0
  for _, s in ipairs(servos) do
    local ok, p, q = pcall(autoTest, s, keep)
    if ok then pass, fail = pass + p, fail + q
    else fail = fail + 1; log("  [FAIL] error: %s", tostring(p)) end
  end
  log("== %d passed, %d failed", pass, fail)
  logFile.close()
  print("Log written to " .. LOG)
else
  print("Usage: servo_test [auto [name] [--keep] | manual [name] | wave [amp] [period] | spin [deg/s]]")
end
